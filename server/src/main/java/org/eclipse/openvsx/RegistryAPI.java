/********************************************************************************
 * Copyright (c) 2019 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx;

import com.google.common.collect.Iterables;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.openvsx.entities.SemanticVersion;
import org.eclipse.openvsx.json.*;
import org.eclipse.openvsx.search.ISearchService;
import org.eclipse.openvsx.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.eclipse.openvsx.util.TargetPlatform.*;

@RestController
public class RegistryAPI {
    private final static int REVIEW_TITLE_SIZE = 255;
    private final static int REVIEW_COMMENT_SIZE = 2048;
    private final static String VERSION_PATH_PARAM_REGEX = "(?:" + SemanticVersion.VERSION_PATH_PARAM_REGEX + ")|latest|pre-release";

    private final LocalRegistryService local;
    private final UpstreamRegistryService upstream;
    private final UserService users;
    private final ObservationRegistry observations;

    public RegistryAPI(
            LocalRegistryService local,
            UpstreamRegistryService upstream,
            UserService users,
            ObservationRegistry observations
    ) {
        this.local = local;
        this.upstream = upstream;
        this.users = users;
        this.observations = observations;
    }

    protected Iterable<IExtensionRegistry> getRegistries() {
        var registries = new ArrayList<IExtensionRegistry>();
        registries.add(local);
        if (upstream.isValid())
            registries.add(upstream);
        return registries;
    }

    @GetMapping(
        path = "/api/{namespace}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of a namespace")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The namespace metadata are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified namespace could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<NamespaceJson> getNamespace(
            @PathVariable @Parameter(description = "Namespace name", example = "redhat")
            String namespace
        ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                        .body(registry.getNamespace(namespace));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = NamespaceJson.error("Namespace not found: " + namespace);
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
        path = "/api/{namespace}/verify-pat",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Check if a personal access token is valid and is allowed to publish in a namespace")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The provided PAT is valid and is allowed to publish extensions in the namespace"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The token has no publishing permission in the namespace or is not valid",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified namespace could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ResultJson> verifyToken(
            @PathVariable @Parameter(description = "Namespace", example = "redhat")
            String namespace,
            @RequestParam @Parameter(description = "A personal access token") String token
    ) {
        try {
            return ResponseEntity.ok(local.verifyToken(namespace, token));
        } catch (NotFoundException exc) {
            var json = ResultJson.error("Namespace not found: " + namespace);
            return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(ResultJson.class);
        }
    }

    @GetMapping(
            path = "/api/{namespace}/details",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation()
    @ApiResponses({})
    public ResponseEntity<NamespaceDetailsJson> getNamespaceDetails(
            @PathVariable @Parameter(description = "Namespace name", example = "redhat")
            String namespace
    ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache().cachePublic())
                        .body(registry.getNamespaceDetails(namespace));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = NamespaceDetailsJson.error("Namespace not found: " + namespace);
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
            path = "/api/{namespace}/logo/{fileName}",
            produces = { MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE }
    )
    @CrossOrigin
    @Operation(summary = "Provides logo of a namespace")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The namespace details are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified namespace could not be found"
        )
    })
    public ResponseEntity<byte[]> getNamespaceLogo(
            @PathVariable @Parameter(description = "Namespace name", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Logo file name", example = "logo-redhat.png")
            String fileName
    ) {
        for (var registry : getRegistries()) {
            try {
                return registry.getNamespaceLogo(namespace, fileName);
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }

        throw new NotFoundException();
    }

    @GetMapping(
        path = "/api/{namespace}/{extension}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of the latest version of an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The extension metadata are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified extension could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ExtensionJson> getExtension(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension
    ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache().cachePublic())
                        .body(registry.getExtension(namespace, extension, null));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = ExtensionJson.error("Extension not found: " + NamingUtil.toExtensionId(namespace, extension));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
        path = "/api/{namespace}/{extension}/{targetPlatform:" + TargetPlatform.NAMES_PATH_PARAM_REGEX + "}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of the latest version of an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The extension metadata are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified extension could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ExtensionJson> getExtension(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable
            @Parameter(
                description = "Target platform",
                example = TargetPlatform.NAME_LINUX_ARM64,
                schema = @Schema(type = "string", allowableValues = {
                    NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                    NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                    NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                    NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                    NAME_WEB, NAME_UNIVERSAL
                })
            )
            CharSequence targetPlatform
    ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                        .body(registry.getExtension(namespace, extension, targetPlatform.toString()));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = ExtensionJson.error("Extension not found: " + NamingUtil.toLogFormat(namespace, extension, targetPlatform.toString(), null));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
        path = "/api/{namespace}/{extension}/{version:" + VERSION_PATH_PARAM_REGEX + "}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of a specific version of an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The extension metadata are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified extension could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ExtensionJson> getExtension(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable @Parameter(description = "Extension version", example = "0.65.0")
            String version
    ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache().cachePublic())
                        .body(registry.getExtension(namespace, extension, null, version));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = ExtensionJson.error("Extension not found: " + NamingUtil.toLogFormat(namespace, extension, version));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
        path = "/api/{namespace}/{extension}/{targetPlatform:" + TargetPlatform.NAMES_PATH_PARAM_REGEX + "}/{version:" + VERSION_PATH_PARAM_REGEX + "}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of a specific version of an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The extension metadata are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified extension could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ExtensionJson> getExtension(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable
            @Parameter(
                description = "Target platform",
                example = TargetPlatform.NAME_LINUX_ARM64,
                schema = @Schema(type = "string", allowableValues = {
                    NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                    NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                    NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                    NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                    NAME_WEB, NAME_UNIVERSAL
                })
            )
            String targetPlatform,
            @PathVariable @Parameter(description = "Extension version", example = "0.65.0")
            String version
    ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                        .body(registry.getExtension(namespace, extension, targetPlatform, version));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = ExtensionJson.error("Extension not found: " + NamingUtil.toLogFormat(namespace, extension, targetPlatform, version));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
            path = "/api/{namespace}/{extension}/versions",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides a map of versions matching an extension")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "The extension versions are returned in JSON format"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "The specified extension could not be found",
                    content = @Content()
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "A client has sent too many requests in a given amount of time",
                    content = @Content(),
                    headers = {
                            @Header(
                                    name = "X-Rate-Limit-Retry-After-Seconds",
                                    description = "Number of seconds to wait after receiving a 429 response",
                                    schema = @Schema(type = "integer", format = "int32")
                            ),
                            @Header(
                                    name = "X-Rate-Limit-Remaining",
                                    description = "Remaining number of requests left",
                                    schema = @Schema(type = "integer", format = "int32")
                            )
                    }
            )
    })
    public ResponseEntity<VersionsJson> getVersions(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @RequestParam(defaultValue = "18")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "18"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset
    ) {
        return handleGetVersions(namespace, extension, null, size, offset);
    }

    @GetMapping(
            path = "/api/{namespace}/{extension}/{targetPlatform:" + TargetPlatform.NAMES_PATH_PARAM_REGEX + "}/versions",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides a map of versions matching an extension")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "The extension versions are returned in JSON format"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "The specified extension could not be found",
                    content = @Content()
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "A client has sent too many requests in a given amount of time",
                    content = @Content(),
                    headers = {
                            @Header(
                                    name = "X-Rate-Limit-Retry-After-Seconds",
                                    description = "Number of seconds to wait after receiving a 429 response",
                                    schema = @Schema(type = "integer", format = "int32")
                            ),
                            @Header(
                                    name = "X-Rate-Limit-Remaining",
                                    description = "Remaining number of requests left",
                                    schema = @Schema(type = "integer", format = "int32")
                            )
                    }
            )
    })
    public ResponseEntity<VersionsJson> getVersions(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable
            @Parameter(
                    description = "Target platform",
                    example = TargetPlatform.NAME_LINUX_ARM64,
                    schema = @Schema(type = "string", allowableValues = {
                            NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                            NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                            NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                            NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                            NAME_WEB, NAME_UNIVERSAL
                    })
            )
            String targetPlatform,
            @RequestParam(defaultValue = "18")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "18"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset
    ) {
        return handleGetVersions(namespace, extension, targetPlatform, size, offset);
    }

    private ResponseEntity<VersionsJson> handleGetVersions(String namespace, String extension, String targetPlatform, int size, int offset) {
        if (size < 0) {
            var json = VersionsJson.error("The parameter 'size' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (offset < 0) {
            var json = VersionsJson.error("The parameter 'offset' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                        .body(registry.getVersions(namespace, extension, targetPlatform, size, offset));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = VersionsJson.error("Extension not found: " + NamingUtil.toLogFormat(namespace, extension, targetPlatform));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
            path = "/api/{namespace}/{extension}/version-references",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides a list of version references matching an extension")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "The extension version references are returned in JSON format"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "The specified extension could not be found",
                    content = @Content()
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "A client has sent too many requests in a given amount of time",
                    content = @Content(),
                    headers = {
                            @Header(
                                    name = "X-Rate-Limit-Retry-After-Seconds",
                                    description = "Number of seconds to wait after receiving a 429 response",
                                    schema = @Schema(type = "integer", format = "int32")
                            ),
                            @Header(
                                    name = "X-Rate-Limit-Remaining",
                                    description = "Remaining number of requests left",
                                    schema = @Schema(type = "integer", format = "int32")
                            )
                    }
            )
    })
    public ResponseEntity<VersionReferencesJson> getVersionReferences(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @RequestParam(defaultValue = "18")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "18"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset
    ) {
        return handleGetVersionReferences(namespace, extension, null, size, offset);
    }

    @GetMapping(
            path = "/api/{namespace}/{extension}/{targetPlatform:" + TargetPlatform.NAMES_PATH_PARAM_REGEX + "}/version-references",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides a list of version references matching an extension")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "The extension version references are returned in JSON format"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "The specified extension could not be found",
                    content = @Content()
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "A client has sent too many requests in a given amount of time",
                    content = @Content(),
                    headers = {
                            @Header(
                                    name = "X-Rate-Limit-Retry-After-Seconds",
                                    description = "Number of seconds to wait after receiving a 429 response",
                                    schema = @Schema(type = "integer", format = "int32")
                            ),
                            @Header(
                                    name = "X-Rate-Limit-Remaining",
                                    description = "Remaining number of requests left",
                                    schema = @Schema(type = "integer", format = "int32")
                            )
                    }
            )
    })
    public ResponseEntity<VersionReferencesJson> getVersionReferences(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable
            @Parameter(
                    description = "Target platform",
                    example = TargetPlatform.NAME_LINUX_ARM64,
                    schema = @Schema(type = "string", allowableValues = {
                            NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                            NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                            NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                            NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                            NAME_WEB, NAME_UNIVERSAL
                    })
            )
            String targetPlatform,
            @RequestParam(defaultValue = "18")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "18"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset
    ) {
        return handleGetVersionReferences(namespace, extension, targetPlatform, size, offset);
    }

    private ResponseEntity<VersionReferencesJson> handleGetVersionReferences(String namespace, String extension, String targetPlatform, int size, int offset) {
        if (size < 0) {
            var json = VersionReferencesJson.error("The parameter 'size' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (offset < 0) {
            var json = VersionReferencesJson.error("The parameter 'offset' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                        .body(registry.getVersionReferences(namespace, extension, targetPlatform, size, offset));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = VersionReferencesJson.error("Extension not found: " + NamingUtil.toLogFormat(namespace, extension, targetPlatform));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping("/api/{namespace}/{extension}/{version:" + VERSION_PATH_PARAM_REGEX + "}/file/**")
    @CrossOrigin
    @Operation(summary = "Access a file packaged by an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The file content is returned"
        ),
        @ApiResponse(
            responseCode = "302",
            description = "The file is found at the specified location",
            content = @Content(),
            headers = @Header(
                name = "Location",
                description = "The actual URL where the file can be accessed",
                schema = @Schema(type = "string")
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified file could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<byte[]> getFile(
            HttpServletRequest request,
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable @Parameter(description = "Extension version", example = "0.65.0")
            String version
    ) {
        var fileName = UrlUtil.extractWildcardPath(request, "/api/{namespace}/{extension}/{version}/file/**");
        for (var registry : getRegistries()) {
            try {
                return registry.getFile(namespace, extension, NAME_UNIVERSAL, version, fileName);
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        throw new NotFoundException();
    }

    @GetMapping("/api/{namespace}/{extension}/{targetPlatform:" + TargetPlatform.NAMES_PATH_PARAM_REGEX + "}/{version:" + VERSION_PATH_PARAM_REGEX + "}/file/**")
    @CrossOrigin
    @Operation(summary = "Access a file packaged by an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The file content is returned"
        ),
        @ApiResponse(
            responseCode = "302",
            description = "The file is found at the specified location",
            content = @Content(),
            headers = @Header(
                name = "Location",
                description = "The actual URL where the file can be accessed",
                schema = @Schema(type = "string")
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified file could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<byte[]> getFile(
            HttpServletRequest request,
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension,
            @PathVariable
            @Parameter(
                description = "Target platform",
                example = TargetPlatform.NAME_LINUX_ARM64,
                schema = @Schema(type = "string", allowableValues = {
                    NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                    NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                    NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                    NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                    NAME_WEB, NAME_UNIVERSAL
                })
            )
            String targetPlatform,
            @PathVariable @Parameter(description = "Extension version", example = "0.65.0")
            String version
    ) {
        var fileName = UrlUtil.extractWildcardPath(request, "/api/{namespace}/{extension}/{targetPlatform}/{version}/file/**");
        for (var registry : getRegistries()) {
            try {
                return registry.getFile(namespace, extension, targetPlatform, version, fileName);
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        throw new NotFoundException();
    }

    @GetMapping(
        path = "/api/{namespace}/{extension}/reviews",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Returns the list of reviews of an extension")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The reviews are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified extension could not be found",
            content = @Content()
        )
    })
    public ResponseEntity<ReviewListJson> getReviews(
            @PathVariable @Parameter(description = "Extension namespace", example = "redhat")
            String namespace,
            @PathVariable @Parameter(description = "Extension name", example = "java")
            String extension
    ) {
        for (var registry : getRegistries()) {
            try {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache().cachePublic())
                        .body(registry.getReviews(namespace, extension));
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }
        var json = ReviewListJson.error("Extension not found: " + NamingUtil.toExtensionId(namespace, extension));
        return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
    }

    @GetMapping(
        path = "/api/-/search",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Search extensions via text entered by a user")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The search results are returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The request contains an invalid parameter value",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{\"error\": \"The parameter 'size' must not be negative.\"}")
            )
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<SearchResultJson> search(
            @RequestParam(required = false)
            @Parameter(description = "Query text for searching", example = "javascript")
            String query,
            @RequestParam(required = false)
            @Parameter(description = "Extension category as shown in the UI", example = "Programming Languages")
            String category,
            @RequestParam(required = false)
            @Parameter(
                description = "Target platform",
                example = TargetPlatform.NAME_LINUX_ARM64,
                schema = @Schema(type = "string", allowableValues = {
                    NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                    NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                    NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                    NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                    NAME_WEB, NAME_UNIVERSAL
                })
            )
            String targetPlatform,
            @RequestParam(defaultValue = "18")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "18"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset,
            @RequestParam(defaultValue = "desc") 
            @Parameter(description = "Descending or ascending sort order", schema = @Schema(type = "string", allowableValues = {"asc", "desc"}))
            String sortOrder,
            @RequestParam(defaultValue = "relevance")
            @Parameter(description = "Sort key (relevance is a weighted mix of various properties)", schema = @Schema(type = "string", allowableValues = {"relevance", "timestamp", "averageRating", "downloadCount"}))
            String sortBy,
            @RequestParam(required = false)
            @Parameter(description = "Whether to include information on all available versions for each returned entry")
            boolean includeAllVersions
    ) {
        if (size < 0) {
            var json = SearchResultJson.error("The parameter 'size' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (offset < 0) {
            var json = SearchResultJson.error("The parameter 'offset' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }

        var options = new ISearchService.Options(query, category, targetPlatform, size, offset, sortOrder, sortBy, includeAllVersions);
        var result = new SearchResultJson();
        result.extensions = new ArrayList<>(size);
        for (var registry : getRegistries()) {
            if (result.extensions.size() >= size) {
                return ResponseEntity.ok(result);
            }
            try {
                var subResult = registry.search(options);
                if(result.extensions.isEmpty() && subResult.extensions != null) {
                    result.extensions.addAll(subResult.extensions);
                } else if (subResult.extensions != null && !subResult.extensions.isEmpty()) {
                    int limit = size - result.extensions.size();
                    var subResultSize = mergeSearchResults(result, subResult.extensions, limit);
                    result.offset += subResult.offset;
                    offset = Math.max(offset - subResult.offset - subResultSize, 0);
                }
                result.totalSize += subResult.totalSize;
            } catch (NotFoundException exc) {
                // Try the next registry
            } catch (ErrorResultException exc) {
                return exc.toResponseEntity(SearchResultJson.class);
            }
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePublic())
                .body(result);
    }

    private int mergeSearchResults(SearchResultJson result, List<SearchEntryJson> entries, int limit) {
        var previousResult = Iterables.limit(result.extensions, result.extensions.size());
        var entriesIter = entries.iterator();
        int mergedEntries = 0;
        while (entriesIter.hasNext() && result.extensions.size() < limit) {
            var next = entriesIter.next();
            if (!Iterables.any(previousResult, ext -> ext.namespace.equals(next.namespace) && ext.name.equals(next.name))) {
                result.extensions.add(next);
                mergedEntries++;
            }
        }
        return mergedEntries;
    }

    @GetMapping(
        path = "/api/v2/-/query",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of extensions matching the given parameters")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Returns the (possibly empty) query results"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The request contains an invalid parameter value"
        )
    })
    public ResponseEntity<QueryResultJson> getQueryV2(
            @RequestParam(required = false)
            @Parameter(description = "Name of a namespace", example = "foo")
            String namespaceName,
            @RequestParam(required = false)
            @Parameter(description = "Name of an extension", example = "bar")
            String extensionName,
            @RequestParam(required = false)
            @Parameter(description = "Version of an extension", example = "1")
            String extensionVersion,
            @RequestParam(required = false)
            @Parameter(description = "Identifier in the form {namespace}.{extension}", example = "foo.bar")
            String extensionId,
            @RequestParam(required = false)
            @Parameter(description = "Universally unique identifier of an extension", example = "5678")
            String extensionUuid,
            @RequestParam(required = false)
            @Parameter(description = "Universally unique identifier of a namespace", example = "1234")
            String namespaceUuid,
            @RequestParam(defaultValue = "links")
            @Parameter(
                    description = "Whether to include all versions of an extension",
                    schema = @Schema(type = "string", allowableValues = { "true", "false", "links" }, defaultValue = "links")
            )
            String includeAllVersions,
            @RequestParam(required = false)
            @Parameter(
                    description = "Target platform",
                    example = TargetPlatform.NAME_LINUX_X64,
                    schema = @Schema(type = "string", allowableValues = {
                    NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                    NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                    NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                    NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                    NAME_WEB, NAME_UNIVERSAL
                })
            )
            String targetPlatform,
            @RequestParam(defaultValue = "100")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "100"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset
    ) {
        if (size < 0) {
            var json = QueryResultJson.error("The parameter 'size' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (offset < 0) {
            var json = QueryResultJson.error("The parameter 'offset' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if(!List.of("true", "false", "links").contains(includeAllVersions)) {
            var json = QueryResultJson.error("Invalid includeAllVersions value: " + includeAllVersions + ".");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }

        var request = new QueryRequestV2();
        request.namespaceName = namespaceName;
        request.extensionName = extensionName;
        request.extensionVersion = extensionVersion;
        request.extensionId = extensionId;
        request.extensionUuid = extensionUuid;
        request.namespaceUuid = namespaceUuid;
        request.includeAllVersions = includeAllVersions;
        request.targetPlatform = targetPlatform;
        request.size = size;
        request.offset = offset;

        var result = new QueryResultJson();
        result.offset = request.offset;
        result.extensions = new ArrayList<>(size);
        for (var registry : getRegistries()) {
            try {
                var subResult = registry.queryV2(request);
                if(result.extensions.isEmpty() && subResult.extensions != null) {
                    result.extensions.addAll(subResult.extensions);
                } else if (subResult.extensions != null && !subResult.extensions.isEmpty()) {
                    int limit = size - result.extensions.size();
                    var subResultSize = mergeQueryResults(result, subResult.extensions, limit);
                    result.offset += subResult.offset;
                    offset = Math.max(offset - subResult.offset - subResultSize, 0);
                }
                result.totalSize += subResult.totalSize;
            } catch (NotFoundException exc) {
                // Try the next registry
            } catch (ErrorResultException exc) {
                return exc.toResponseEntity(QueryResultJson.class);
            }
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                .body(result);
    }

    @GetMapping(
        path = "/api/-/query",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of extensions matching the given parameters")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Returns the (possibly empty) query results"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The request contains an invalid parameter value",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{\"error\":\"The 'extensionId' parameter must have the format 'namespace.extension'.\"}")
            )
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<QueryResultJson> getQuery(
            @RequestParam(required = false)
            @Parameter(description = "Name of a namespace", example = "foo")
            String namespaceName,
            @RequestParam(required = false)
            @Parameter(description = "Name of an extension", example = "bar")
            String extensionName,
            @RequestParam(required = false)
            @Parameter(description = "Version of an extension", example = "1")
            String extensionVersion,
            @RequestParam(required = false)
            @Parameter(description = "Identifier in the form {namespace}.{extension}", example = "foo.bar")
            String extensionId,
            @RequestParam(required = false)
            @Parameter(description = "Universally unique identifier of an extension", example = "5678")
            String extensionUuid,
            @RequestParam(required = false)
            @Parameter(description = "Universally unique identifier of a namespace", example = "1234")
            String namespaceUuid,
            @RequestParam(required = false)
            @Parameter(description = "Whether to include all versions of an extension, ignored if extensionVersion is specified")
            boolean includeAllVersions,
            @RequestParam(required = false)
            @Parameter(
                description = "Target platform",
                example = TargetPlatform.NAME_LINUX_X64,
                schema = @Schema(type = "string", allowableValues = {
                    NAME_WIN32_X64, NAME_WIN32_IA32, NAME_WIN32_ARM64,
                    NAME_LINUX_X64, NAME_LINUX_ARM64, NAME_LINUX_ARMHF,
                    NAME_ALPINE_X64, NAME_ALPINE_ARM64,
                    NAME_DARWIN_X64, NAME_DARWIN_ARM64,
                    NAME_WEB, NAME_UNIVERSAL
                })
            )
            String targetPlatform,
            @RequestParam(defaultValue = "100")
            @Parameter(description = "Maximal number of entries to return", schema = @Schema(type = "integer", minimum = "0", defaultValue = "100"))
            int size,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "Number of entries to skip (usually a multiple of the page size)", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            int offset
    ) {
        if (size < 0) {
            var json = QueryResultJson.error("The parameter 'size' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (offset < 0) {
            var json = QueryResultJson.error("The parameter 'offset' must not be negative.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        
        var request = new QueryRequest();
        request.namespaceName = namespaceName;
        request.extensionName = extensionName;
        request.extensionVersion = extensionVersion;
        request.extensionId = extensionId;
        request.extensionUuid = extensionUuid;
        request.namespaceUuid = namespaceUuid;
        request.includeAllVersions = includeAllVersions;
        request.targetPlatform = targetPlatform;
        request.size = size;
        request.offset = offset;

        var result = new QueryResultJson();
        result.offset = request.offset;
        result.extensions = new ArrayList<>(size);
        for (var registry : getRegistries()) {
            try {
                var subResult = registry.query(request);
                if(result.extensions.isEmpty() && subResult.extensions != null) {
                    result.extensions.addAll(subResult.extensions);
                } else if (subResult.extensions != null && !subResult.extensions.isEmpty()) {
                    int limit = size - result.extensions.size();
                    var subResultSize = mergeQueryResults(result, subResult.extensions, limit);
                    result.offset += subResult.offset;
                    offset = Math.max(offset - subResult.offset - subResultSize, 0);
                }
                result.totalSize += subResult.totalSize;
            } catch (NotFoundException exc) {
                // Try the next registry
            } catch (ErrorResultException exc) {
                return exc.toResponseEntity(QueryResultJson.class);
            }
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                .body(result);
    }

    private int mergeQueryResults(QueryResultJson result, List<ExtensionJson> entries, int limit) {
        var previousResult = Iterables.limit(result.extensions, result.extensions.size());
        var entriesIter = entries.iterator();
        int mergedEntries = 0;
        while (entriesIter.hasNext() && result.extensions.size() < limit) {
            var next = entriesIter.next();
            if (!Iterables.any(previousResult, ext -> ext.namespace.equals(next.namespace) && ext.name.equals(next.name))) {
                result.extensions.add(next);
                mergedEntries++;
            }
        }
        return mergedEntries;
    }

    @PostMapping(
        path = "/api/-/query",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Provides metadata of extensions matching the given parameters. Deprecated: use GET /api/-/query instead.", deprecated = true)
    @ApiResponses({
        @ApiResponse(
            responseCode = "301",
            description = "Returns redirect to GET /api/-/query."
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<QueryResultJson> postQuery(
            @RequestBody @Parameter(description = "Parameters of the metadata query")
            QueryParamJson param
    ) {
        var location = UrlUtil.createApiUrl(UrlUtil.getBaseUrl(), "api", "-", "query");
        location = UrlUtil.addQuery(location, param.toQueryParams());
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .location(URI.create(location))
                .build();
    }

    @PostMapping(
        path = "/api/-/namespace/create",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Create a namespace")
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Successfully created the namespace",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{ \"success\": \"Created namespace foobar\" }")
            ),
            headers = @Header(
                name = "Location",
                description = "The URL of the namespace metadata",
                schema = @Schema(type = "string")
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The namespace could not be created",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{ \"error\": \"Invalid access token.\" }")
            )
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ResultJson> createNamespace(
            @RequestBody @Parameter(description = "Describes the namespace to create")
            NamespaceJson namespace,
            @RequestParam @Parameter(description = "A personal access token")
            String token
    ) {
        if (namespace == null) {
            return ResponseEntity.ok(ResultJson.error("No JSON input."));
        }
        if (StringUtils.isEmpty(namespace.name)) {
            return ResponseEntity.ok(ResultJson.error("Missing required property 'name'."));
        }
        try {
            var json = local.createNamespace(namespace, token);
            var serverUrl = UrlUtil.getBaseUrl();
            var url = UrlUtil.createApiUrl(serverUrl, "api", namespace.name);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create(url))
                    .body(json);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @PostMapping(
        path = "/api/user/namespace/create",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Create a namespace",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Describes the namespace to create",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(ref = "NamespaceJson")),
            required = true
        )
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Successfully created the namespace",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value="{ \"success\": \"Created namespace foobar\" }")
            ),
            headers = @Header(
                name = "Location",
                description = "The URL of the namespace metadata",
                schema = @Schema(type = "string")
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The namespace could not be created",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value="{ \"error\": \"Invalid access token.\" }")
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "User is not logged in"
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ResultJson> createNamespace(
            @RequestBody NamespaceJson namespace
    ) {
        var user = users.findLoggedInUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        if (namespace == null) {
            return ResponseEntity.ok(ResultJson.error("No JSON input."));
        }
        if (StringUtils.isEmpty(namespace.name)) {
            return ResponseEntity.ok(ResultJson.error("Missing required property 'name'."));
        }
        try {
            var json = local.createNamespace(namespace, user);
            var serverUrl = UrlUtil.getBaseUrl();
            var url = UrlUtil.createApiUrl(serverUrl, "api", namespace.name);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create(url))
                    .body(json);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @PostMapping(
        path = "/api/-/publish",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Publish an extension by uploading a vsix file",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Uploaded vsix file to publish",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")),
            required = true
        )
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Successfully published the extension",
            headers = @Header(
                name = "Location",
                description = "The URL of the extension metadata",
                schema = @Schema(type = "string")
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The extension could not be published",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{ \"error\": \"Invalid access token.\" }")
            )
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ExtensionJson> publish(
            InputStream content,
            @RequestParam @Parameter(description = "A personal access token") String token
    ) {
        return Observation.createNotStarted("RegistryAPI#publish", observations).observe(() -> {
            try {
                var json = local.publish(content, token);
                var serverUrl = UrlUtil.getBaseUrl();
                var url = UrlUtil.createApiVersionUrl(serverUrl, json);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .location(URI.create(url))
                        .body(json);
            } catch (ErrorResultException exc) {
                return exc.toResponseEntity(ExtensionJson.class);
            }
        });
    }

    @PostMapping(
        path = "/api/user/publish",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Publish an extension by uploading a vsix file",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Uploaded vsix file to publish",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")),
            required = true
        )
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Successfully published the extension",
            headers = @Header(
                name = "Location",
                description = "The URL of the extension metadata",
                schema = @Schema(type = "string")
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "The extension could not be published",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value="{ \"error\": \"Unknown publisher: foobar\" }")
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "User is not logged in"
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<ExtensionJson> publish(InputStream content) {
        return Observation.createNotStarted("RegistryAPI#publish", observations).observe(() -> {
            try {
                var user = users.findLoggedInUser();
                if (user == null) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }

                var json = local.publish(content, user);
                var serverUrl = UrlUtil.getBaseUrl();
                var url = UrlUtil.createApiUrl(serverUrl, "api", json.namespace, json.name, json.version);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .location(URI.create(url))
                        .body(json);
            } catch (ErrorResultException exc) {
                return exc.toResponseEntity(ExtensionJson.class);
            }
        });
    }

    @PostMapping(
        path = "/api/{namespace}/{extension}/review",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ResultJson> postReview(
            @RequestBody(required = false) ReviewJson review,
            @PathVariable String namespace,
            @PathVariable String extension
    ) {
        if (review == null) {
            var json = ResultJson.error("No JSON input.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (review.rating < 0 || review.rating > 5) {
            var json = ResultJson.error("The rating must be an integer number between 0 and 5.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (review.title != null && review.title.length() > REVIEW_TITLE_SIZE) {
            var json = ResultJson.error("The title must not be longer than " + REVIEW_TITLE_SIZE + " characters.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        if (review.comment != null && review.comment.length() > REVIEW_COMMENT_SIZE) {
            var json = ResultJson.error("The review must not be longer than " + REVIEW_COMMENT_SIZE + " characters.");
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
        var json = local.postReview(review, namespace, extension);
        if (json.error == null) {
            return new ResponseEntity<>(json, HttpStatus.CREATED);
        } else {
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
    }

    @PostMapping(
        path = "/api/{namespace}/{extension}/review/delete",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ResultJson> deleteReview(@PathVariable String namespace, @PathVariable String extension) {
        var json = local.deleteReview(namespace, extension);
        if (json.error == null) {
            return ResponseEntity.ok(json);
        } else {
            return new ResponseEntity<>(json, HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping(
        path = "/api/-/public-key/{publicId}",
        produces = MediaType.TEXT_PLAIN_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Access a public key file")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The file content is returned"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The specified public key file could not be found",
            content = @Content()
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            content = @Content(),
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<String> getPublicKey(
            @PathVariable @Parameter(description = "Public ID of a public key file", example = "92dea4de-80b5-4577-b27d-44cdcda82c63")
            String publicId
    ) {
        for (var registry : getRegistries()) {
            try {
                var publicKeyText = registry.getPublicKey(publicId);
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                        .body(publicKeyText);
            } catch (NotFoundException exc) {
                // Try the next registry
            }
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping(path = "/api/version", produces = MediaType.APPLICATION_JSON_VALUE)
    @CrossOrigin
    @Operation(summary = "Return the registry version")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "The registry version is returned in JSON format"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "The registry version could not be determined"
        ),
        @ApiResponse(
            responseCode = "429",
            description = "A client has sent too many requests in a given amount of time",
            headers = {
                @Header(
                    name = "X-Rate-Limit-Retry-After-Seconds",
                    description = "Number of seconds to wait after receiving a 429 response",
                    schema = @Schema(type = "integer", format = "int32")
                ),
                @Header(
                    name = "X-Rate-Limit-Remaining",
                    description = "Remaining number of requests left",
                    schema = @Schema(type = "integer", format = "int32")
                )
            }
        )
    })
    public ResponseEntity<RegistryVersionJson> getServerVersion() {
        try {
            return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                        .body(local.getRegistryVersion());
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(RegistryVersionJson.class);
        }
    }
}
