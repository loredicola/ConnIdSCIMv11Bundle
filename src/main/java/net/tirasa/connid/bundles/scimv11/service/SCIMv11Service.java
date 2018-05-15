/**
 * Copyright (C) 2018 ConnId (connid-dev@googlegroups.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.tirasa.connid.bundles.scimv11.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import net.tirasa.connid.bundles.scimv11.dto.PagedResults;
import net.tirasa.connid.bundles.scimv11.dto.SCIMAttribute;
import net.tirasa.connid.bundles.scimv11.dto.SCIMSchema;
import net.tirasa.connid.bundles.scimv11.dto.User;
import net.tirasa.connid.bundles.scimv11.utils.SCIMv11Attributes;
import net.tirasa.connid.bundles.scimv11.utils.SCIMv11Utils;
import org.apache.cxf.jaxrs.client.WebClient;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.common.security.SecurityUtil;

public class SCIMv11Service {

    private static final Log LOG = Log.getLog(SCIMv11Service.class);

    private final String baseAddress;

    private final String username;

    private final GuardedString password;

    private final String bearer;

    private final String accept;

    private final String contentType;

    private final String customAttributesJSON;

    private final String updateMethod;

    public final static String RESPONSE_ERRORS = "Errors";

    public final static String RESPONSE_RESOURCES = "Resources";

    public SCIMv11Service(
            final String baseAddress,
            final String username,
            final GuardedString password,
            final String accept,
            final String contentType,
            final String bearer,
            final String customAttributesJSON,
            final String updateMethod) {
        this.baseAddress = baseAddress;
        this.username = username;
        this.password = password;
        this.accept = accept;
        this.contentType = contentType;
        this.bearer = bearer;
        this.customAttributesJSON = customAttributesJSON;
        this.updateMethod = updateMethod;
    }

    protected WebClient getWebclient(final String path, final Map<String, String> params) {
        WebClient webClient = WebClient.create(baseAddress,
                username,
                password == null ? null : SecurityUtil.decrypt(password),
                null)
                .type(accept)
                .accept(contentType)
                .path(path);
        if (bearer != null) {
            webClient.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }

        if (params != null) {
            for (Entry<String, String> entry : params.entrySet()) {
                webClient.query(entry.getKey(), entry.getValue()); // will encode parameter
            }
        }

        return webClient;
    }

    protected JsonNode doGetFromService(final WebClient webClient) {
        LOG.ok("webClient current URL : {0}", webClient.getCurrentURI());
        JsonNode result = null;

        try {
            Response response = webClient.get();
            String responseAsString = response.readEntity(String.class);
            checkServiceErrors(response);
            result = SCIMv11Utils.MAPPER.readTree(responseAsString);
            checkServiceResultErrors(result, response);

            if (result.isArray()
                    && (!result.has(RESPONSE_RESOURCES) || result.get(RESPONSE_RESOURCES).isNull())) {
                SCIMv11Utils.handleGeneralError("Wrong response from GET request: " + responseAsString);
            }

        } catch (IOException ex) {
            LOG.error(ex, "While retrieving data from SCIM API");
        }

        return result;
    }

    protected void doCreate(final User user, final WebClient webClient) {
        LOG.ok("webClient current URL : {0}", webClient.getCurrentURI());
        Response response;
        String payload;

        try {
            // check custom attributes
            JsonNode customAttributesNode = buildCustomAttributesNode(customAttributesJSON, user);
            if (customAttributesNode != null) {
                // add custom attributes to payload
                JsonNode userNode = null;
                try {
                    userNode = mergeNodes(SCIMv11Utils.MAPPER.readTree(SCIMv11Utils.MAPPER.writeValueAsString(user)),
                            customAttributesNode);
                } catch (JsonProcessingException ex) {
                    SCIMv11Utils.handleGeneralError("While converting user to node", ex);
                }
                payload = SCIMv11Utils.MAPPER.writeValueAsString(userNode);
            } else {
                // no custom attributes
                payload = SCIMv11Utils.MAPPER.writeValueAsString(user);
            }
            response = webClient.post(payload);

            if (response == null) {
                SCIMv11Utils.handleGeneralError("While creating User - no response");
            } else {
                String value = SCIMv11Attributes.USER_ATTRIBUTE_ID;
                String responseAsString = response.readEntity(String.class);
                JsonNode responseObj = SCIMv11Utils.MAPPER.readTree(responseAsString);
                if (responseObj.hasNonNull(value)) {
                    user.setId(responseObj.get(value).textValue());
                } else {
                    SCIMv11Utils.handleGeneralError(
                            "While getting " + value + " value for created User - Response : " + responseAsString);
                }
            }
        } catch (IOException ex) {
            SCIMv11Utils.handleGeneralError("While creating User", ex);
        }
    }

    protected JsonNode doUpdate(final User user, final WebClient webClient) {
        LOG.ok("webClient current URL : {0}", webClient.getCurrentURI());
        JsonNode result = null;
        Response response = null;
        String payload;
        if (updateMethod.equalsIgnoreCase("PATCH")) {
            WebClient.getConfig(webClient).getRequestContext().put("use.async.http.conduit", true);
        }

        try {
            // check custom attributes
            JsonNode customAttributesNode = buildCustomAttributesNode(customAttributesJSON, user);
            if (customAttributesNode != null) {
                // add custom attributes to payload
                JsonNode userNode = null;
                try {
                    userNode = mergeNodes(SCIMv11Utils.MAPPER.readTree(SCIMv11Utils.MAPPER.writeValueAsString(user)),
                            customAttributesNode);
                } catch (JsonProcessingException ex) {
                    SCIMv11Utils.handleGeneralError("While converting user to node", ex);
                }
                payload = SCIMv11Utils.MAPPER.writeValueAsString(userNode);
            } else {
                // no custom attributes
                payload = SCIMv11Utils.MAPPER.writeValueAsString(user);
            }

            if (updateMethod.equalsIgnoreCase("PATCH")) {
                response = webClient.invoke("PATCH", payload);
            } else {
                response = webClient.put(payload);
            }

            if (response == null) {
                SCIMv11Utils.handleGeneralError("While updating User - no response");
            } else {
                String responseAsString = response.readEntity(String.class);
                checkServiceErrors(response);
                result = SCIMv11Utils.MAPPER.readTree(responseAsString);
                checkServiceResultErrors(result, response);
            }
        } catch (IOException ex) {
            SCIMv11Utils.handleGeneralError("While updating User", ex);
        }

        return result;
    }

    protected void doDelete(final String userId, final WebClient webClient) {
        LOG.ok("webClient current URL : {0}", webClient.getCurrentURI());
        int status = webClient.delete().getStatus();
        if (status != Status.NO_CONTENT.getStatusCode() && status != Status.OK.getStatusCode()) {
            throw new NoSuchEntityException(userId);
        }
    }

    protected void doActivate(final String userId, final WebClient webClient) {
        LOG.ok("webClient current URL : {0}", webClient.getCurrentURI());
        Response response;
        try {
            ObjectNode userIdNode = SCIMv11Utils.MAPPER.createObjectNode();
            userIdNode.set("user_id", userIdNode.textNode(userId));

            response = webClient.post(SCIMv11Utils.MAPPER.writeValueAsString(userIdNode));
            if (response == null) {
                SCIMv11Utils.handleGeneralError("While activating User - no response");
            } else {
                String responseAsString = response.readEntity(String.class);
                LOG.ok("Response after activating user: {0}", responseAsString);
            }
        } catch (IOException ex) {
            SCIMv11Utils.handleGeneralError("While activating User", ex);
        }
    }

    private void checkServiceErrors(final Response response) {
        if (response.getStatus() == Status.NOT_FOUND.getStatusCode()) {
            throw new NoSuchEntityException(response.readEntity(String.class));
        } else if (response.getStatus() != Status.OK.getStatusCode()
                && response.getStatus() != Status.ACCEPTED.getStatusCode()) {
            SCIMv11Utils.handleGeneralError("While executing GET request: " + response.readEntity(String.class));
        }
    }

    private void checkServiceResultErrors(final JsonNode node, final Response response) {
        if (node.has(RESPONSE_ERRORS)) {
            SCIMv11Utils.handleGeneralError(response.readEntity(String.class));
        }
    }

    private JsonNode buildCustomAttributesNode(final String customAttributesJSON, final User user) {
        JsonNode rootNode = null;
        if (StringUtil.isNotBlank(customAttributesJSON) && !user.getSCIMCustomAttributes().isEmpty()) {
            rootNode = SCIMv11Utils.MAPPER.createObjectNode();
            for (SCIMAttribute scimAttribute : user.getSCIMCustomAttributes().keySet()) {
                if (scimAttribute.getType().equals(SCIMv11Attributes.SCIM_SCHEMA_TYPE_COMPLEX)) {
                    for (SCIMAttribute scimSubAttribute : scimAttribute.getSubAttributes()) {
                        buildCustomSimpleAttributeNode(rootNode, scimSubAttribute, user);
                    }
                } else {
                    buildCustomSimpleAttributeNode(rootNode, scimAttribute, user);
                }
            }
        }

        return rootNode;
    }

    private void buildCustomSimpleAttributeNode(final JsonNode rootNode,
            final SCIMAttribute scimAttribute,
            final User user) {
        ObjectNode newNode = SCIMv11Utils.MAPPER.createObjectNode();
        List<Object> values = user.getSCIMCustomAttributes().get(scimAttribute);
        Object value = null;

        if (!scimAttribute.getMultiValued()) {
            value = values.get(0);
        }
        String mainNodeKey = scimAttribute.getSchema();
        String currentNodeKey = scimAttribute.getName();

        if (scimAttribute.getType().equals(SCIMv11Attributes.SCIM_SCHEMA_TYPE_COMPLEX)) {
            LOG.warn("Too many 'complex' type custom attributes, while parsing custom attribute {0} with schema {1}",
                    currentNodeKey,
                    mainNodeKey);
        } else {
            if (mainNodeKey.contains(SCIMv11Attributes.SCIM_SCHEMA_EXTENSION)) {
                if (rootNode.has(mainNodeKey)) {
                    ((ObjectNode) rootNode.get(mainNodeKey)).putPOJO(currentNodeKey,
                            values.size() > 1 ? values : values.get(0));
                } else {
                    newNode.putPOJO(currentNodeKey,
                            value == null ? values : value);
                    ((ObjectNode) rootNode).set(mainNodeKey, newNode);
                }
            } else {
                ((ObjectNode) rootNode).putPOJO(currentNodeKey,
                        value == null ? values : value);
            }
        }
    }

    private JsonNode mergeNodes(final JsonNode mainNode, final JsonNode updateNode) {
        Iterator<String> fieldNames = updateNode.fieldNames();

        while (fieldNames.hasNext()) {
            String updatedFieldName = fieldNames.next();
            JsonNode valueToBeUpdated = mainNode.get(updatedFieldName);
            JsonNode updatedValue = updateNode.get(updatedFieldName);

            // If the node is an @ArrayNode
            if (valueToBeUpdated != null && valueToBeUpdated.isArray() && updatedValue.isArray()) {
                // running a loop for all elements of the updated ArrayNode
                for (int i = 0; i < updatedValue.size(); i++) {
                    JsonNode updatedChildNode = updatedValue.get(i);
                    // Create a new Node in the node that should be updated, if there was no corresponding node in it
                    // Use-case - where the updateNode will have a new element in its Array
                    if (valueToBeUpdated.size() <= i) {
                        ((ArrayNode) valueToBeUpdated).add(updatedChildNode);
                    }
                    // getting reference for the node to be updated
                    JsonNode childNodeToBeUpdated = valueToBeUpdated.get(i);
                    mergeNodes(childNodeToBeUpdated, updatedChildNode);
                }
                // if the Node is an @ObjectNode
            } else if (valueToBeUpdated != null && valueToBeUpdated.isObject()) {
                mergeNodes(valueToBeUpdated, updatedValue);
            } else {
                if (mainNode instanceof ObjectNode) {
                    ((ObjectNode) mainNode).replace(updatedFieldName, updatedValue);
                }
            }
        }

        return mainNode;
    }

    public static SCIMSchema extractSCIMSchemas(final String node) {
        SCIMSchema customAttributesObj = null;
        try {
            customAttributesObj = SCIMv11Utils.MAPPER.readValue(
                    node,
                    SCIMSchema.class);
        } catch (IOException ex) {
            LOG.error(ex, "While parsing custom attributes JSON object, taken from connector configuration");
        }

        return customAttributesObj;
    }

    protected void readCustomAttributes(final User user, final JsonNode node) {
        if (StringUtil.isNotBlank(customAttributesJSON)) {
            SCIMSchema scimSchema = extractSCIMSchemas(customAttributesJSON);

            if (scimSchema != null && !scimSchema.getAttributes().isEmpty()) {
                for (SCIMAttribute attribute : scimSchema.getAttributes()) {
                    List<JsonNode> foundWithSchemaAsKey = node.findValues(attribute.getSchema());
                    if (!foundWithSchemaAsKey.isEmpty()) {
                        List<Object> values = new ArrayList<>();
                        values.add(foundWithSchemaAsKey.get(0).get(attribute.getName()).textValue());
                        user.getReturnedCustomAttributes().put(
                                attribute.getSchema()
                                        .concat(".")
                                        .concat(attribute.getName()),
                                values);
                    }
                }
            }
        }
    }

    protected void readCustomAttributes(final PagedResults<User> resources, final JsonNode node) {
        for (User resource : resources.getResources()) {
            readCustomAttributes(resource, node);
        }
    }
}
