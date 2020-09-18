/*
 * Copyright (C) 2020  Awesome Technologies Innovationslabor GmbH. All rights reserved.
 *
 *
 * BearerToken.java is free software: you can redistribute it and/or modify it under the
 * terms of the Apache License, Version 2.0 (the License);
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * The software is provided "AS IS", without any warranty of any kind, express or implied,
 * including but not limited to the warranties of merchantability, fitness for a particular
 * purpose and noninfringement, in no event shall the authors or copyright holders be
 * liable for any claim, damages or other liability, whether in action of contract, tort or
 * otherwise, arising from, out of or in connection with the software or the use or other
 * dealings in the software.
 *
 * See README file for the full disclaimer information and LICENSE file for full license
 * information in the project root.
 *
 * @author Awesome Technologies Innovationslabor GmbH
 *
 * This class represents a JWT bearer token.
 */

package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;

public class BearerToken {
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PushInterceptor.class);
  private static final String BEARER = "BEARER ";
  private static final java.util.Base64.Decoder decoder = java.util.Base64.getUrlDecoder();

  private DecodedJWT jwt;

  public BearerToken(String authHeader) {
    if (authHeader.toUpperCase().startsWith(BEARER) == false)
      throw new AuthenticationException("Invalid Authorization header. Missing Bearer prefix");
    jwt = JWT.decode(authHeader.substring(BEARER.length()));
    ourLog.info("JWT: header " + getHeaderJSON() + "; payload " + getPayloadJSON());
  }

  public String getHeaderJSON() {
    return new String(decoder.decode(jwt.getHeader()));
  }

  public String getPayloadJSON() {
    return new String(decoder.decode(jwt.getPayload()));
  }

  public Boolean isAdmin() {
    JSONObject obj = new JSONObject(getPayloadJSON());
    // extract the role
    final JSONArray roles = obj.getJSONObject("realm_access").getJSONArray("roles");

    for (int i = 0; i < roles.length(); i++) {
      IdDt id = new IdDt(roles.getString(i));
      if (id.getResourceType().equals("Administrator")) return true;
    }
    return false;
  }

  public List<IdDt> getAuthorizedOrganizations() {
    List<IdDt> myOrgIds = new ArrayList<>();
    try {
      // TODO Use JWT claims instead of decoding JSON by hand
      // build a JSON object
      JSONObject obj = new JSONObject(getPayloadJSON());
      // extract the role
      final JSONArray roles = obj.getJSONObject("realm_access").getJSONArray("roles");

      for (int i = 0; i < roles.length(); i++) {
        IdDt id = new IdDt(roles.getString(i));
        if (id.getResourceType().equals("Organization")) myOrgIds.add(id);
      }
      if (myOrgIds.size() == 0) {  // Throw an HTTP 401
        throw new AuthenticationException("No access role defined");
      }
    } catch (JSONException e) {  // Throw an HTTP 401
      e.printStackTrace();
      throw new AuthenticationException("Invalid authorization header value");
    }
    return myOrgIds;
  }
}
