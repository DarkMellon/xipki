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

package org.xipki.ca.certprofile.xijson;

import static org.xipki.util.Args.notNull;

import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x509.qualified.TypeOfBiometricData;
import org.xipki.ca.certprofile.xijson.conf.BiometricInfo;
import org.xipki.ca.certprofile.xijson.conf.BiometricInfo.BiometricTypeType;
import org.xipki.ca.certprofile.xijson.conf.X509ProfileType;
import org.xipki.security.util.AlgorithmUtil;
import org.xipki.util.TripleState;

/**
 * Control of the extension BiometricInfo.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class BiometricInfoOption {

  private final Set<Integer> predefinedTypes;

  private final Set<ASN1ObjectIdentifier> idTypes;

  private final Set<ASN1ObjectIdentifier> hashAlgorithms;

  private final TripleState sourceDataUriOccurrence;

  public BiometricInfoOption(BiometricInfo value)
      throws NoSuchAlgorithmException {
    notNull(value, "value");

    this.sourceDataUriOccurrence = value.getIncludeSourceDataUri();
    this.hashAlgorithms = X509ProfileType.toOidSet(value.getHashAlgorithms());

    for (ASN1ObjectIdentifier m : hashAlgorithms) {
      AlgorithmUtil.getHashOutputSizeInOctets(m);
    }

    this.predefinedTypes = new HashSet<>();
    this.idTypes = new HashSet<>();
    for (BiometricTypeType m : value.getTypes()) {
      if (m.getPredefined() != null) {
        predefinedTypes.add(m.getPredefined().getValue());
      } else if (m.getOid() != null) {
        idTypes.add(new ASN1ObjectIdentifier(m.getOid().getOid()));
      } else {
        throw new IllegalStateException("should not reach here, invalid biometricType");
      }
    }
  } // constructor

  public boolean isTypePermitted(TypeOfBiometricData type) {
    notNull(type, "type");

    if (type.isPredefined()) {
      return predefinedTypes.contains(type.getPredefinedBiometricType());
    } else {
      return idTypes.contains(type.getBiometricDataOid());
    }
  }

  public boolean isHashAlgorithmPermitted(ASN1ObjectIdentifier hashAlgorithm) {
    notNull(hashAlgorithm, "hashAlgorithm");
    return hashAlgorithms.contains(hashAlgorithm);
  }

  public TripleState getSourceDataUriOccurrence() {
    return sourceDataUriOccurrence;
  }

}
