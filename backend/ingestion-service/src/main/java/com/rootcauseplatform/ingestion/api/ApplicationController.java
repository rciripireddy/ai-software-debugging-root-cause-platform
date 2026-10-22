package com.rootcauseplatform.ingestion.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rootcauseplatform.ingestion.api.EventQueryResponses.ApplicationResponse;
import com.rootcauseplatform.ingestion.api.EventQueryResponses.ItemsResponse;
import com.rootcauseplatform.ingestion.service.EventQueryService;

/** Registered applications (name, service, environment), e.g. for dashboard filter options. */
@RestController
@RequestMapping(path = "/api/v1/applications", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApplicationController {

    private final EventQueryService queryService;

    public ApplicationController(EventQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ItemsResponse<ApplicationResponse> list() {
        return new ItemsResponse<>(queryService.applications().stream().map(ApplicationResponse::from).toList());
    }
}
