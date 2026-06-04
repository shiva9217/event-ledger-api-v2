package com.eventledger.gateway;

import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The optional metadata object must be stored verbatim and returned unchanged (raw JSON).
 */
class MetadataTest extends WireMockGatewayTest {

    private long createReturningId(String body) throws Exception {
        String json = mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(json).read("$.id", Long.class);
    }

    @Test
    void metadataObject_isStoredAndReturnedVerbatim() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        long id = createReturningId("""
                {"eventId":"meta-1","accountId":"acct-meta","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z",
                 "metadata":{"source":"mainframe-batch","batchId":"B-9042","retries":3}}""");

        mvc.perform(get("/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.source", is("mainframe-batch")))
                .andExpect(jsonPath("$.metadata.batchId", is("B-9042")))
                .andExpect(jsonPath("$.metadata.retries", is(3)));
    }

    @Test
    void missingMetadata_isOmittedFromResponse() throws Exception {
        // EventResponse is annotated @JsonInclude(NON_NULL), so absent metadata is omitted.
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        long id = createReturningId("""
                {"eventId":"meta-2","accountId":"acct-meta","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""");

        mvc.perform(get("/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata").doesNotExist());
    }
}
