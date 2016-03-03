/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2013 - 2016 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License (version 3
 * or later at your option) as published by the Free Software Foundation
 * with the addition of the following permission added to Section 15 as
 * permitted in Section 7(a):
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.pki.ca.dbtool.port;

import java.io.File;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.commons.common.ConfPairs;
import org.xipki.commons.common.ProcessLog;
import org.xipki.commons.common.util.IoUtil;
import org.xipki.commons.common.util.ParamUtil;
import org.xipki.commons.common.util.XmlUtil;
import org.xipki.commons.datasource.api.DataSourceWrapper;
import org.xipki.commons.datasource.api.springframework.dao.DataAccessException;
import org.xipki.commons.dbtool.InvalidInputException;
import org.xipki.commons.security.api.HashAlgoType;
import org.xipki.commons.security.api.HashCalculator;
import org.xipki.commons.security.api.util.X509Util;
import org.xipki.pki.ca.dbtool.jaxb.ca.CAConfigurationType;
import org.xipki.pki.ca.dbtool.jaxb.ca.CaHasPublisherType;
import org.xipki.pki.ca.dbtool.jaxb.ca.CaType;
import org.xipki.pki.ca.dbtool.jaxb.ca.CertStoreType;
import org.xipki.pki.ca.dbtool.jaxb.ca.CertStoreType.Cas;
import org.xipki.pki.ca.dbtool.jaxb.ca.CertstoreCaType;
import org.xipki.pki.ca.dbtool.jaxb.ca.NameIdType;
import org.xipki.pki.ca.dbtool.jaxb.ca.PublisherType;
import org.xipki.pki.ca.dbtool.xmlio.CaCertType;
import org.xipki.pki.ca.dbtool.xmlio.CaCertsReader;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

class OcspCertStoreFromCaDbImporter extends AbstractOcspCertStoreDbImporter {

    private static final class ImportStatements {
        final PreparedStatement psCert;
        final PreparedStatement psCerthash;
        final PreparedStatement psRawCert;

        ImportStatements(
                PreparedStatement psCert,
                PreparedStatement psCerthash,
                PreparedStatement psRawCert) {
            this.psCert = psCert;
            this.psCerthash = psCerthash;
            this.psRawCert = psRawCert;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(OcspCertStoreFromCaDbImporter.class);

    private final Unmarshaller unmarshaller;

    private final String publisherName;

    private final boolean resume;

    private final int numCertsPerCommit;

    OcspCertStoreFromCaDbImporter(
            final DataSourceWrapper dataSource,
            final Unmarshaller unmarshaller,
            final String srcDir,
            final String publisherName,
            final int numCertsPerCommit,
            final boolean resume,
            final AtomicBoolean stopMe,
            final boolean evaluateOnly)
    throws Exception {
        super(dataSource, srcDir, stopMe, evaluateOnly);

        this.unmarshaller = ParamUtil.requireNonNull("unmarshaller", unmarshaller);
        this.publisherName = ParamUtil.requireNonBlank("publisherName", publisherName);
        this.numCertsPerCommit = ParamUtil.requireMin("numCertsPerCommit", numCertsPerCommit, 1);

        File processLogFile = new File(baseDir, DbPorter.IMPORT_TO_OCSP_PROCESS_LOG_FILENAME);
        if (resume) {
            if (!processLogFile.exists()) {
                throw new InvalidInputException("could not process with '--resume' option");
            }
        } else {
            if (processLogFile.exists()) {
                throw new InvalidInputException(
                        "please either specify '--resume' option or delete the file "
                        + processLogFile.getPath() + " first");
            }
        }
        this.resume = resume;
    }

    public void importToDb()
    throws Exception {
        CertStoreType certstore;
        try {
            @SuppressWarnings("unchecked")
            JAXBElement<CertStoreType> root = (JAXBElement<CertStoreType>)
                    unmarshaller.unmarshal(new File(baseDir, FILENAME_CA_CERTSTORE));
            certstore = root.getValue();
        } catch (JAXBException ex) {
            throw XmlUtil.convert(ex);
        }

        if (certstore.getVersion() > VERSION) {
            throw new InvalidInputException(
                    "could not import CertStore greater than " + VERSION + ": "
                    + certstore.getVersion());
        }

        CAConfigurationType caConf;
        try {
            @SuppressWarnings("unchecked")
            JAXBElement<CAConfigurationType> rootCaConf = (JAXBElement<CAConfigurationType>)
                    unmarshaller.unmarshal(
                            new File(baseDir + File.separator + FILENAME_CA_CONFIGURATION));
            caConf = rootCaConf.getValue();
        } catch (JAXBException ex) {
            throw XmlUtil.convert(ex);
        }

        if (caConf.getVersion() > VERSION) {
            throw new InvalidInputException("could not import CA Configuration greater than "
                    + VERSION + ": " + certstore.getVersion());
        }

        System.out.println("importing CA certstore to OCSP database");
        try {
            if (!resume) {
                dropIndexes();
            }
            PublisherType publisherType = null;
            for (PublisherType type : caConf.getPublishers().getPublisher()) {
                if (publisherName.equals(type.getName())) {
                    publisherType = type;
                    break;
                }
            }

            if (publisherType == null) {
                throw new InvalidInputException("unknown publisher " + publisherName);
            }

            String type = publisherType.getType();
            if (!"ocsp".equalsIgnoreCase(type)) {
                throw new InvalidInputException("Unkwown publisher type " + type);
            }

            ConfPairs confPairs = new ConfPairs(getValue(publisherType.getConf()));
            String v = confPairs.getValue("publish.goodcerts");
            boolean revokedOnly = false;
            if (v != null) {
                revokedOnly = !Boolean.parseBoolean(v);
            }

            Set<String> relatedCaNames = new HashSet<>();
            for (CaHasPublisherType ctype : caConf.getCaHasPublishers().getCaHasPublisher()) {
                if (ctype.getPublisherName().equals(publisherName)) {
                    relatedCaNames.add(ctype.getCaName());
                }
            }

            List<CaType> relatedCas = new LinkedList<>();
            for (CaType cType : caConf.getCas().getCa()) {
                if (relatedCaNames.contains(cType.getName())) {
                    relatedCas.add(cType);
                }
            }

            if (relatedCas.isEmpty()) {
                System.out.println("No CA has publisher " + publisherName);
                return;
            }

            Map<Integer, String> profileMap = new HashMap<Integer, String>();
            for (NameIdType ni : certstore.getProfiles().getProfile()) {
                profileMap.put(ni.getId(), ni.getName());
            }

            List<Integer> relatedCaIds;
            if (resume) {
                relatedCaIds = getIssuerIds(certstore.getCas(), relatedCas);
            } else {
                relatedCaIds = importIssuer(certstore.getCas(), relatedCas);
            }

            File processLogFile = new File(baseDir, DbPorter.IMPORT_TO_OCSP_PROCESS_LOG_FILENAME);
            importCert(certstore, profileMap, revokedOnly, relatedCaIds, processLogFile);
            recoverIndexes();
            processLogFile.delete();
        } catch (Exception ex) {
            System.err.println("error while importing OCSP certstore to database");
            throw ex;
        }
        System.out.println(" imported OCSP certstore to database");
    } // method importToDb

    private List<Integer> getIssuerIds(
            final Cas issuers,
            final List<CaType> cas)
    throws IOException {
        List<Integer> relatedCaIds = new LinkedList<>();
        for (CertstoreCaType issuer : issuers.getCa()) {
            String b64Cert = getValue(issuer.getCert());
            byte[] encodedCert = Base64.decode(b64Cert);

            // retrieve the revocation information of the CA, if possible
            CaType ca = null;
            for (CaType caType : cas) {
                if (Arrays.equals(encodedCert, Base64.decode(getValue(caType.getCert())))) {
                    ca = caType;
                    break;
                }
            }

            if (ca == null) {
                continue;
            }
            relatedCaIds.add(issuer.getId());
        }
        return relatedCaIds;
    }

    private List<Integer> importIssuer(
            final Cas issuers,
            final List<CaType> cas)
    throws DataAccessException, CertificateException, IOException {
        System.out.println("importing table ISSUER");
        final String sql = SQL_ADD_ISSUER;
        PreparedStatement ps = prepareStatement(sql);

        List<Integer> relatedCaIds = new LinkedList<>();

        try {
            for (CertstoreCaType issuer : issuers.getCa()) {
                doImportIssuer(issuer, sql, ps, cas, relatedCaIds);
            }
        } finally {
            releaseResources(ps, null);
        }

        System.out.println(" imported table ISSUER");
        return relatedCaIds;
    }

    private void doImportIssuer(
            final CertstoreCaType issuer,
            final String sql,
            final PreparedStatement ps,
            final List<CaType> cas,
            final List<Integer> relatedCaIds)
    throws IOException, DataAccessException, CertificateException {
        try {
            String b64Cert = getValue(issuer.getCert());
            byte[] encodedCert = Base64.decode(b64Cert);

            // retrieve the revocation information of the CA, if possible
            CaType ca = null;
            for (CaType caType : cas) {
                if (Arrays.equals(encodedCert, Base64.decode(getValue(caType.getCert())))) {
                    ca = caType;
                    break;
                }
            }

            if (ca == null) {
                return;
            }

            relatedCaIds.add(issuer.getId());

            Certificate c;
            byte[] encodedName;
            try {
                c = Certificate.getInstance(encodedCert);
                encodedName = c.getSubject().getEncoded("DER");
            } catch (Exception ex) {
                LOG.error("could not parse certificate of issuer {}", issuer.getId());
                LOG.debug("could not parse certificate of issuer " + issuer.getId(), ex);
                if (ex instanceof CertificateException) {
                    throw (CertificateException) ex;
                } else {
                    throw new CertificateException(ex.getMessage(), ex);
                }
            }
            byte[] encodedKey = c.getSubjectPublicKeyInfo().getPublicKeyData().getBytes();

            int idx = 1;
            ps.setInt(idx++, issuer.getId());
            ps.setString(idx++,
                    X509Util.cutX500Name(c.getSubject(), maxX500nameLen));
            ps.setLong(idx++,
                    c.getTBSCertificate().getStartDate().getDate().getTime() / 1000);
            ps.setLong(idx++,
                    c.getTBSCertificate().getEndDate().getDate().getTime() / 1000);
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA1, encodedName));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA1, encodedKey));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA224, encodedName));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA224, encodedKey));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA256, encodedName));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA256, encodedKey));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA384, encodedName));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA384, encodedKey));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA512, encodedName));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA512, encodedKey));
            ps.setString(idx++,
                    HashCalculator.base64Hash(HashAlgoType.SHA1, encodedCert));
            ps.setString(idx++, b64Cert);

            setBoolean(ps, idx++, ca.isRevoked());
            setInt(ps, idx++, ca.getRevReason());
            setLong(ps, idx++, ca.getRevTime());
            setLong(ps, idx++, ca.getRevInvTime());

            ps.execute();
        } catch (SQLException ex) {
            System.err.println("error while importing issuer with id=" + issuer.getId());
            throw translate(sql, ex);
        } catch (CertificateException ex) {
            System.err.println("error while importing issuer with id=" + issuer.getId());
            throw ex;
        }
    } // method doImportIssuer

    private void importCert(
            final CertStoreType certstore,
            final Map<Integer, String> profileMap,
            final boolean revokedOnly,
            final List<Integer> caIds,
            final File processLogFile)
    throws Exception {
        int numProcessedBefore = 0;
        int minId = 1;
        if (processLogFile.exists()) {
            byte[] content = IoUtil.read(processLogFile);
            if (content != null && content.length > 2) {
                String str = new String(content);
                if (str.trim().equalsIgnoreCase(MSG_CERTS_FINISHED)) {
                    return;
                }

                StringTokenizer st = new StringTokenizer(str, ":");
                numProcessedBefore = Integer.parseInt(st.nextToken());
                minId = Integer.parseInt(st.nextToken());
                minId++;
            }
        }

        deleteCertGreatherThan(minId - 1, LOG);

        final long total = certstore.getCountCerts() - numProcessedBefore;
        final ProcessLog processLog = new ProcessLog(total);
        // all initial values for importLog will be not evaluated, so just any number
        final ProcessLog importLog = new ProcessLog(total);

        System.out.println(getImportingText() + "certificates from ID " + minId);
        processLog.printHeader();

        PreparedStatement psCert = prepareStatement(SQL_ADD_CERT);
        PreparedStatement psCerthash = prepareStatement(SQL_ADD_CHASH);
        PreparedStatement psRawCert = prepareStatement(SQL_ADD_CRAW);
        ImportStatements statments = new ImportStatements(psCert, psCerthash, psRawCert);

        DbPortFileNameIterator certsFileIterator = new DbPortFileNameIterator(certsListFile);
        try {
            while (certsFileIterator.hasNext()) {
                String certsFile = certsDir + File.separator + certsFileIterator.next();
                // extract the toId from the filename
                int fromIdx = certsFile.indexOf('-');
                int toIdx = certsFile.indexOf(".zip");
                if (fromIdx != -1 && toIdx != -1) {
                    try {
                        long toId = Integer.parseInt(certsFile.substring(fromIdx + 1, toIdx));
                        if (toId < minId) {
                            // try next file
                            continue;
                        }
                    } catch (Exception ex) {
                        LOG.warn("invalid file name '{}', but will still be processed", certsFile);
                    }
                } else {
                    LOG.warn("invalid file name '{}', but will still be processed", certsFile);
                }

                try {
                    int lastId = doImportCert(statments,
                            certsFile, profileMap, revokedOnly, caIds, minId,
                            processLogFile, processLog, numProcessedBefore, importLog);
                    minId = lastId + 1;
                } catch (Exception ex) {
                    System.err.println("\nerror while importing certificates from file "
                            + certsFile + ".\nplease continue with the option '--resume'");
                    LOG.error("Exception", ex);
                    throw ex;
                }
            }
        } finally {
            releaseResources(psCert, null);
            releaseResources(psCerthash, null);
            releaseResources(psRawCert, null);
            certsFileIterator.close();
        }

        processLog.printTrailer();
        DbPorter.echoToFile(MSG_CERTS_FINISHED, processLogFile);
        System.out.println("processed " + processLog.getNumProcessed() + " and "
                + getImportedText() + importLog.getNumProcessed() + " certificates");
    } // method importCert

    private int doImportCert(
            final ImportStatements statments,
            final String certsZipFile,
            final Map<Integer, String> profileMap,
            final boolean revokedOnly,
            final List<Integer> caIds,
            final int minId,
            final File processLogFile,
            final ProcessLog processLog,
            final int numProcessedInLastProcess,
            final ProcessLog importLog)
    throws Exception {
        ZipFile zipFile = new ZipFile(new File(certsZipFile));
        ZipEntry certsXmlEntry = zipFile.getEntry("certs.xml");

        CaCertsReader certs;
        try {
            certs = new CaCertsReader(zipFile.getInputStream(certsXmlEntry));
        } catch (Exception ex) {
            try {
                zipFile.close();
            } catch (Exception e2) {
            }
            throw ex;
        }

        disableAutoCommit();

        PreparedStatement psCert = statments.psCert;
        PreparedStatement psCerthash = statments.psCerthash;
        PreparedStatement psRawCert = statments.psRawCert;

        try {
            int numProcessedEntriesInBatch = 0;
            int numImportedEntriesInBatch = 0;
            int lastSuccessfulCertId = 0;

            while (certs.hasNext()) {
                if (stopMe.get()) {
                    throw new InterruptedException("interrupted by the user");
                }

                CaCertType cert = (CaCertType) certs.next();

                int id = cert.getId();
                lastSuccessfulCertId = id;
                if (id < minId) {
                    continue;
                }

                numProcessedEntriesInBatch++;

                if (!revokedOnly || cert.getRev().booleanValue()) {
                    int caId = cert.getCaId();
                    if (caIds.contains(caId)) {
                        numImportedEntriesInBatch++;

                        String filename = cert.getFile();

                        // rawcert
                        ZipEntry certZipEnty = zipFile.getEntry(filename);
                        // rawcert
                        byte[] encodedCert = IoUtil.read(zipFile.getInputStream(certZipEnty));

                        TBSCertificate c;
                        try {
                            Certificate cc = Certificate.getInstance(encodedCert);
                            c = cc.getTBSCertificate();
                        } catch (RuntimeException ex) {
                            LOG.error("could not parse certificate in file {}", filename);
                            LOG.debug("could not parse certificate in file " + filename, ex);
                            throw new CertificateException(ex.getMessage(), ex);
                        }

                        // cert
                        String seqName = "CID";
                        int currentId = (int) dataSource.nextSeqValue(null, seqName);

                        try {
                            int idx = 1;
                            psCert.setInt(idx++, currentId);
                            psCert.setInt(idx++, caId);
                            psCert.setLong(idx++,
                                    c.getSerialNumber().getPositiveValue().longValue());
                            psCert.setLong(idx++, cert.getUpdate());
                            psCert.setLong(idx++, c.getStartDate().getDate().getTime() / 1000);
                            psCert.setLong(idx++, c.getEndDate().getDate().getTime() / 1000);
                            setBoolean(psCert, idx++, cert.getRev());
                            setInt(psCert, idx++, cert.getRr());
                            setLong(psCert, idx++, cert.getRt());
                            setLong(psCert, idx++, cert.getRit());

                            int certprofileId = cert.getPid();
                            String certprofileName = profileMap.get(certprofileId);
                            psCert.setString(idx++, certprofileName);
                            psCert.addBatch();
                        } catch (SQLException ex) {
                            throw translate(SQL_ADD_CERT, ex);
                        }

                        // certhash
                        try {
                            int idx = 1;
                            psCerthash.setInt(idx++, currentId);
                            psCerthash.setString(idx++,
                                    HashCalculator.base64Hash(HashAlgoType.SHA1, encodedCert));
                            psCerthash.setString(idx++,
                                    HashCalculator.base64Hash(HashAlgoType.SHA224, encodedCert));
                            psCerthash.setString(idx++,
                                    HashCalculator.base64Hash(HashAlgoType.SHA256, encodedCert));
                            psCerthash.setString(idx++,
                                    HashCalculator.base64Hash(HashAlgoType.SHA384, encodedCert));
                            psCerthash.setString(idx++,
                                    HashCalculator.base64Hash(HashAlgoType.SHA512, encodedCert));
                            psCerthash.addBatch();
                        } catch (SQLException ex) {
                            throw translate(SQL_ADD_CHASH, ex);
                        }

                        // rawcert
                        try {
                            int idx = 1;
                            psRawCert.setInt(idx++, currentId);
                            psRawCert.setString(idx++,
                                    X509Util.cutX500Name(c.getSubject(), maxX500nameLen));
                            psRawCert.setString(idx++, Base64.toBase64String(encodedCert));
                            psRawCert.addBatch();
                        } catch (SQLException ex) {
                            throw translate(SQL_ADD_CRAW, ex);
                        }
                    } // end if (caIds.contains(caId))
                } // end if (revokedOnly

                boolean isLastBlock = !certs.hasNext();

                if (numImportedEntriesInBatch > 0
                        && (numImportedEntriesInBatch % this.numCertsPerCommit == 0
                                || isLastBlock)) {
                    if (evaulateOnly) {
                        psCert.clearBatch();
                        psCerthash.clearBatch();
                        psRawCert.clearBatch();
                    } else {
                        String sql = null;
                        try {
                            sql = SQL_ADD_CERT;
                            psCert.executeBatch();

                            sql = SQL_ADD_CHASH;
                            psCerthash.executeBatch();

                            sql = SQL_ADD_CRAW;
                            psRawCert.executeBatch();

                            sql = null;
                            commit("(commit import cert to OCSP)");
                        } catch (Throwable th) {
                            rollback();
                            deleteCertGreatherThan(lastSuccessfulCertId, LOG);
                            if (th instanceof SQLException) {
                                throw translate(sql, (SQLException) th);
                            } else if (th instanceof Exception) {
                                throw (Exception) th;
                            } else {
                                throw new Exception(th);
                            }
                        }
                    }

                    lastSuccessfulCertId = id;
                    processLog.addNumProcessed(numProcessedEntriesInBatch);
                    importLog.addNumProcessed(numImportedEntriesInBatch);
                    numProcessedEntriesInBatch = 0;
                    numImportedEntriesInBatch = 0;
                    echoToFile(
                            (numProcessedInLastProcess + processLog.getNumProcessed())
                            + ":" + lastSuccessfulCertId,
                            processLogFile);
                    processLog.printStatus();
                } else if (isLastBlock) {
                    lastSuccessfulCertId = id;
                    processLog.addNumProcessed(numProcessedEntriesInBatch);
                    importLog.addNumProcessed(numImportedEntriesInBatch);
                    numProcessedEntriesInBatch = 0;
                    numImportedEntriesInBatch = 0;
                    echoToFile(
                            (numProcessedInLastProcess + processLog.getNumProcessed())
                            + ":" + lastSuccessfulCertId,
                            processLogFile);
                    processLog.printStatus();
                }
                // if (numImportedEntriesInBatch
            } // end for

            return lastSuccessfulCertId;
        } finally {
            try {
                recoverAutoCommit();
            } catch (DataAccessException ex) {
            }
            zipFile.close();
        }
    } // method doImportCert

}
