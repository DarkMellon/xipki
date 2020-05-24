/*
 *
 * Copyright (c) 2013 - 2020 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.qa.shell;

import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Enumeration;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.jcajce.spec.SM2ParameterSpec;
import org.xipki.security.HashAlgo;
import org.xipki.security.SignatureAlgoControl;
import org.xipki.security.XiSecurityException;
import org.xipki.security.pkcs11.P11CryptService;
import org.xipki.security.pkcs11.P11CryptServiceFactory;
import org.xipki.security.pkcs11.P11Module;
import org.xipki.security.pkcs11.P11ObjectIdentifier;
import org.xipki.security.pkcs11.P11Slot;
import org.xipki.security.pkcs11.P11SlotIdentifier;
import org.xipki.security.pkcs11.P11TokenException;
import org.xipki.security.pkcs11.provider.XiProvider;
import org.xipki.security.pkcs11.provider.XiSM2ParameterSpec;
import org.xipki.security.util.AlgorithmUtil;
import org.xipki.shell.Completers;
import org.xipki.shell.IllegalCmdParamException;
import org.xipki.shell.XiAction;
import org.xipki.util.Hex;
import org.xipki.util.StringUtil;

/**
 * Actions for PKCS#11 security.
 *
 * @author Lijun Liao
 */

public class QaP11Actions {

  @Command(scope = "qa", name = "p11prov-sm2-test",
      description = "test the SM2 implementation of Xipki PKCS#11 JCA/JCE provider")
  @Service
  public static class P11provSm2Test extends P11SecurityAction {

    @Option(name = "--id",
        description = "id of the private key in the PKCS#11 device\n"
            + "either keyId or keyLabel must be specified")
    protected String id;

    @Option(name = "--label",
        description = "label of the private key in the PKCS#11 device\n"
            + "either keyId or keyLabel must be specified")
    protected String label;

    @Option(name = "--verbose", aliases = "-v",
        description = "show object information verbosely")
    private Boolean verbose = Boolean.FALSE;

    @Option(name = "--ida", description = "IDA (ID user A)")
    protected String ida;

    @Override
    protected Object execute0() throws Exception {
      KeyStore ks = KeyStore.getInstance("PKCS11", XiProvider.PROVIDER_NAME);
      ks.load(null, null);
      if (verbose.booleanValue()) {
        println("available aliases:");
        Enumeration<?> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
          String alias2 = (String) aliases.nextElement();
          println("    " + alias2);
        }
      }

      String alias = getAlias();
      println("alias: " + alias);
      PrivateKey key = (PrivateKey) ks.getKey(alias, null);
      if (key == null) {
        println("could not find key with alias '" + alias + "'");
        return null;
      }

      Certificate cert = ks.getCertificate(alias);
      if (cert == null) {
        println("could not find certificate to verify signature");
        return null;
      }

      String sigAlgo = "SM3withSM2";
      println("signature algorithm: " + sigAlgo);
      Signature sig = Signature.getInstance(sigAlgo, XiProvider.PROVIDER_NAME);

      if (StringUtil.isNotBlank(ida)) {
        sig.setParameter(new XiSM2ParameterSpec(StringUtil.toUtf8Bytes(ida)));
      }

      sig.initSign(key);

      byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
      sig.update(data);
      byte[] signature = sig.sign(); // CHECKSTYLE:SKIP
      println("signature created successfully");

      Signature ver = Signature.getInstance(sigAlgo, "BC");
      if (StringUtil.isNotBlank(ida)) {
        ver.setParameter(new SM2ParameterSpec(StringUtil.toUtf8Bytes(ida)));
      }

      ver.initVerify(cert.getPublicKey());
      ver.update(data);
      boolean valid = ver.verify(signature);
      println("signature valid: " + valid);
      return null;
    } // method execute0

    private String getAlias() throws IllegalCmdParamException {
      if (label != null) {
        return StringUtil.concat(moduleName, "#slotindex-", slotIndex.toString(),
            "#keylabel-", label);
      } else if (id != null) {
        return StringUtil.concat(moduleName, "#slotindex-", slotIndex.toString(),
            "#keyid-", id.toLowerCase());
      } else {
        throw new IllegalCmdParamException("either id or label must be specified");
      }
    }

  } // class P11provSm2Test

  @Command(scope = "qa", name = "p11prov-test",
      description = "test the Xipki PKCS#11 JCA/JCE provider")
  @Service
  public static class P11provTest extends P11SecurityAction {

    @Option(name = "--id",
        description = "id of the private key in the PKCS#11 device\n"
            + "either keyId or keyLabel must be specified")
    protected String id;

    @Option(name = "--label",
        description = "label of the private key in the PKCS#11 device\n"
            + "either keyId or keyLabel must be specified")
    protected String label;

    @Option(name = "--verbose", aliases = "-v", description = "show object information verbosely")
    private Boolean verbose = Boolean.FALSE;

    @Option(name = "--hash", description = "hash algorithm name")
    @Completion(Completers.HashAlgCompleter.class)
    protected String hashAlgo = "SHA256";

    @Option(name = "--rsa-mgf1",
        description = "whether to use the RSAPSS MGF1 for the POPO computation\n"
            + "(only applied to RSA key)")
    private Boolean rsaMgf1 = Boolean.FALSE;

    @Option(name = "--dsa-plain",
        description = "whether to use the Plain DSA for the POPO computation\n"
            + "(only applied to ECDSA key)")
    private Boolean dsaPlain = Boolean.FALSE;

    @Option(name = "--gm",
        description = "whether to use the chinese GM algorithm for the POPO computation\n"
            + "(only applied to EC key with GM curves)")
    private Boolean gm = Boolean.FALSE;

    @Override
    protected Object execute0() throws Exception {
      KeyStore ks = KeyStore.getInstance("PKCS11", XiProvider.PROVIDER_NAME);
      ks.load(null, null);
      if (verbose.booleanValue()) {
        println("available aliases:");
        Enumeration<?> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
          String alias2 = (String) aliases.nextElement();
          println("    " + alias2);
        }
      }

      String alias = getAlias();
      println("alias: " + alias);
      PrivateKey key = (PrivateKey) ks.getKey(alias, null);
      if (key == null) {
        println("could not find key with alias '" + alias + "'");
        return null;
      }

      Certificate cert = ks.getCertificate(alias);
      if (cert == null) {
        println("could not find certificate to verify signature");
        return null;
      }
      PublicKey pubKey = cert.getPublicKey();

      String sigAlgo = getSignatureAlgo(pubKey);
      println("signature algorithm: " + sigAlgo);
      Signature sig = Signature.getInstance(sigAlgo, XiProvider.PROVIDER_NAME);
      sig.initSign(key);

      byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
      sig.update(data);
      byte[] signature = sig.sign(); // CHECKSTYLE:SKIP
      println("signature created successfully");

      Signature ver = Signature.getInstance(sigAlgo, "BC");
      ver.initVerify(pubKey);
      ver.update(data);
      boolean valid = ver.verify(signature);
      println("signature valid: " + valid);
      return null;
    } // method execute0

    private String getAlias() throws IllegalCmdParamException {
      if (label != null) {
        return StringUtil.concat(moduleName, "#slotindex-", slotIndex.toString(),
            "#keylabel-", label);
      } else if (id != null) {
        return StringUtil.concat(moduleName, "#slotindex-", slotIndex.toString(),
            "#keyid-", id.toLowerCase());
      } else {
        throw new IllegalCmdParamException("either id or label must be specified");
      }
    }

    private String getSignatureAlgo(PublicKey pubKey) throws NoSuchAlgorithmException {
      SignatureAlgoControl algoControl = new SignatureAlgoControl(rsaMgf1, dsaPlain, gm);
      AlgorithmIdentifier sigAlgId = AlgorithmUtil.getSigAlgId(pubKey,
          HashAlgo.getNonNullInstance(hashAlgo), algoControl);
      return AlgorithmUtil.getSignatureAlgoName(sigAlgId);
    }

  } // class P11provTest

  public abstract static class P11SecurityAction extends XiAction {

    protected static final String DEFAULT_P11MODULE_NAME =
        P11CryptServiceFactory.DEFAULT_P11MODULE_NAME;

    @Option(name = "--slot", required = true, description = "slot index")
    protected Integer slotIndex;

    @Option(name = "--module", description = "name of the PKCS#11 module")
    protected String moduleName = DEFAULT_P11MODULE_NAME;

    @Reference (optional = true)
    protected P11CryptServiceFactory p11CryptServiceFactory;

    protected P11Slot getSlot()
        throws XiSecurityException, P11TokenException, IllegalCmdParamException {
      P11Module module = getP11Module(moduleName);
      P11SlotIdentifier slotId = module.getSlotIdForIndex(slotIndex);
      return module.getSlot(slotId);
    }

    protected P11Module getP11Module(String moduleName)
        throws XiSecurityException, P11TokenException, IllegalCmdParamException {
      P11CryptService p11Service = p11CryptServiceFactory.getP11CryptService(moduleName);
      if (p11Service == null) {
        throw new IllegalCmdParamException("undefined module " + moduleName);
      }
      return p11Service.getModule();
    }

    public P11ObjectIdentifier getObjectIdentifier(String hexId, String label)
        throws IllegalCmdParamException, XiSecurityException, P11TokenException {
      P11Slot slot = getSlot();
      P11ObjectIdentifier objIdentifier;
      if (hexId != null && label == null) {
        objIdentifier = slot.getObjectId(Hex.decode(hexId), null);
      } else if (hexId == null && label != null) {
        objIdentifier = slot.getObjectId(null, label);
      } else {
        throw new IllegalCmdParamException(
            "exactly one of keyId or keyLabel should be specified");
      }
      return objIdentifier;
    }

  } // class P11SecurityAction

}
