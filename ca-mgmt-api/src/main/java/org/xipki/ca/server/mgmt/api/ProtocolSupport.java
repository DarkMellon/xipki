/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
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

package org.xipki.ca.server.mgmt.api;

import java.util.StringTokenizer;

import org.xipki.ca.api.CaUris;
import org.xipki.util.StringUtil;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class ProtocolSupport {

  private static final String CMP = "cmp";

  private static final String REST = "rest";

  private static final String SCEP = "scep";

  private boolean cmp;

  private boolean rest;

  private boolean scep;

  public ProtocolSupport(String encoded) {
    if (StringUtil.isBlank(encoded)) {
      return;
    }

    StringTokenizer st = new StringTokenizer(encoded, ",");
    while (st.hasMoreTokens()) {
      String token = st.nextToken();
      if (CMP.equalsIgnoreCase(token)) {
        cmp = true;
      } else if (REST.equalsIgnoreCase(token)) {
        rest = true;
      } else if (SCEP.equalsIgnoreCase(token)) {
        scep = true;
      }
    }
  }

  public ProtocolSupport(boolean cmp, boolean rest, boolean scep) {
    this.cmp = cmp;
    this.rest = rest;
    this.scep = scep;
  }

  public boolean supportsCmp() {
    return cmp;
  }

  public void setCmp(boolean cmp) {
    this.cmp = cmp;
  }

  public boolean supportsRest() {
    return rest;
  }

  public void setRest(boolean rest) {
    this.rest = rest;
  }

  public boolean supportsScep() {
    return scep;
  }

  public void setScep(boolean scep) {
    this.scep = scep;
  }

  public boolean equals(Object obj) {
    if (! (obj instanceof ProtocolSupport)) {
      return false;
    }

    ProtocolSupport other = (ProtocolSupport) obj;
    return cmp == other.cmp
        && rest == other.rest
        && scep == other.scep;
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Supported protocols:");
    sb.append("\n  CMP:").append(cmp);
    sb.append("\n  REST:").append(rest);
    sb.append("\n  SCEP:").append(scep);
    return sb.toString();
  }

  public String getEncoded() {
    StringBuilder st = new StringBuilder(15);
    if (cmp) {
      st.append(CMP).append(",");
    }

    if (rest) {
      st.append(REST).append(",");
    }

    if (scep) {
      st.append(SCEP).append(",");
    }

    return st.length() == 0  ? "" : st.deleteCharAt(st.length() - 1).toString();
  }

}
