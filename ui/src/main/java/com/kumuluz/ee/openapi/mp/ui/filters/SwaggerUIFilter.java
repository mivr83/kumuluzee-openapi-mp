/*
 *  Copyright (c) 2014-2017 Kumuluz and/or its affiliates
 *  and other contributors as indicated by the @author tags and
 *  the contributor list.
 *
 *  Licensed under the MIT License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/MIT
 *
 *  The software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, express or
 *  implied, including but not limited to the warranties of merchantability,
 *  fitness for a particular purpose and noninfringement. in no event shall the
 *  authors or copyright holders be liable for any claim, damages or other
 *  liability, whether in an action of contract, tort or otherwise, arising from,
 *  out of or in connection with the software or the use or other dealings in the
 *  software. See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.kumuluz.ee.openapi.mp.ui.filters;

import io.smallrye.openapi.api.OpenApiDocument;
import org.eclipse.jetty.server.Request;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.servers.Server;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.kumuluz.ee.configuration.utils.ConfigurationUtil;

import static com.kumuluz.ee.openapi.mp.ui.OpenApiMpUiExtension.OAUTH_HTML_PAGE;
import static com.kumuluz.ee.openapi.mp.ui.util.PathUtils.removeTrailingAndAddLeadingSlash;

/**
 * Configures default parameters for Swagger UI when coming from root URL.
 *
 * @author Zvone Gazvoda
 * @author Urban Malc
 * @since 1.1.0
 */
public class SwaggerUIFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(SwaggerUIFilter.class.getName());


    private static final String X_ORIGINAL_URI = "X-Original-URI";
    private static final String OAUTH_QUERY = "&oauth2RedirectUrl=";
    private static final String URL_QUERY = "/?url=";
    private static final List<String> LOCAL_HOST_ALIASES = Arrays.asList("localhost", "loopback", "127.0.0.1", "[::1]");


    private String specUrl;
    private String uiPath;
    private String oauth2RedirectUrl;
    private String specPath;
    private boolean isAutoConfigure;
    private boolean isServerListUpdateEnabled;
    private boolean isXURICheckEnabled;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.specUrl = filterConfig.getInitParameter("specUrl");
        this.uiPath = filterConfig.getInitParameter("uiPath");
        this.oauth2RedirectUrl = filterConfig.getInitParameter("oauth2RedirectUrl");
        this.specPath = filterConfig.getInitParameter("specPath");
        getServerAutoConfigurationSettings();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {

        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;

        String path = httpServletRequest.getContextPath()+httpServletRequest.getServletPath();

        // check if request is for UI
        if (path.contains(uiPath)) {
            // match static files and urls with existing parameter url=... set
            Pattern staticFiles = Pattern.compile("(\\.css|\\.js|\\.html|\\.png|url=)");
            String requestQueryString = httpServletRequest.getRequestURI();
            if (httpServletRequest.getQueryString() != null) {
                requestQueryString += httpServletRequest.getQueryString();
            }
            if (!staticFiles.matcher(requestQueryString).find()) {
                // not a static file, redirect to appropriate url, if enabled perform auto-configuration
                String redirectUrl = isAutoConfigure ? createRedirectPathFromRequest(httpServletRequest) : getStaticRedirect();
                httpServletResponse.sendRedirect(redirectUrl);
            } else {
                // static file, leave as is
                filterChain.doFilter(httpServletRequest, httpServletResponse);
            }
        } else {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    private String getStaticRedirect() {
        return uiPath + URL_QUERY + specUrl + OAUTH_QUERY + oauth2RedirectUrl;
    }

    private String createRedirectPathFromRequest(HttpServletRequest httpServletRequest) {
        try {
            Request request = ((Request) httpServletRequest);
            // gather URL and other information from http request
            String rootPath = getRootPath(request);
            URI rootURI = URI.create(getRootUrl(request) + rootPath);

            // update server list if enabled
            if (isServerListUpdateEnabled) {
                updateServerList(rootURI);
            }

            String rootUrl = rootURI.toString();
            return rootPath + uiPath + URL_QUERY + rootUrl + specPath + OAUTH_QUERY + rootUrl + uiPath + OAUTH_HTML_PAGE;
        } catch (Exception e) {
            LOG.warning("Failed to dynamically resolve redirect from request header, " +
                                "falling back to static configuration. Reason of failure: " + e.getMessage());
            return getStaticRedirect();
        }
    }

    private String getRootUrl(Request request) {
        // note: maybe compose root url from header too when isXURICheckEnable true (X-Forwarded-Host, X-Forwarded-Proto)
        return request.getRootURL().toString();
    }

    private String getRootPath(Request request) {
        if (isXURICheckEnabled) {
            // retrieve X-Original-URI if available
            String originalURI = request.getHeader(X_ORIGINAL_URI);
            if (originalURI != null) {
                // remove uiPath part (which is contained in X-Org... path provided by ingres for example)
                String rootPathFromHeader = originalURI.replaceFirst(uiPath + ".*\\z", "");
                return removeTrailingAndAddLeadingSlash(rootPathFromHeader);
            }
        }

        return "";
    }

    /**
     * Updates Open API servers list.
     * If URL is already in list or it is loopback address then no action is taken.
     */
    private synchronized void updateServerList(URI rootURI) {
        OpenAPI openApiDocument = getOpenAPI();

        List<Server> oldServers =
                Optional.ofNullable(openApiDocument.getServers()).orElse(Collections.emptyList());

        // update server list and in case where server was add also move it to the top
        if (isNotInServerList(rootURI, oldServers)) {
            // note: there is possibility that simultaneous getting of openapi .json and invoking
            // ui endpoint will result in 2 different lists of servers
            ArrayList<Server> newServers = new ArrayList<>();
            newServers.add(new CustomServer(rootURI.toString()));
            newServers.addAll(oldServers);
            openApiDocument.setServers(newServers);
        }
    }

    private boolean isNotInServerList(URI rootURI, List<Server> oldServers) {
        boolean isRootUrlLoopback = isLoopbackHost(rootURI.getHost());
        return oldServers
                .stream()
                // first check if servers are match,then compare if urls are loopback and are matched
                .noneMatch(s -> rootURI.toString().equalsIgnoreCase(s.getUrl()) ||
                            isRootUrlLoopback && isLoopbackUrl(s.getUrl()));
    }

    private void getServerAutoConfigurationSettings() {
        // gather config
        ConfigurationUtil configurationUtil = getConfigUtil();
        this.isAutoConfigure = configurationUtil
                .getBoolean("kumuluzee.openapi-mp.ui.server-auto-config.enabled")
                .orElse(false);
        this.isServerListUpdateEnabled = configurationUtil
                .getBoolean("kumuluzee.openapi-mp.ui.server-auto-config.update-servers")
                .orElse(true);
        this.isXURICheckEnabled = configurationUtil
                .getBoolean("kumuluzee.openapi-mp.ui.server-auto-config.original-uri-check")
                .orElse(false);
    }

    /**
     * Determines if supplied host is loopback address or not.
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }

        String lowerCaseHost = host.toLowerCase();
        return LOCAL_HOST_ALIASES.stream().anyMatch(lowerCaseHost::startsWith);
    }

    /**
     * Determines if supplied host is loopback address or not.
     */
    public static boolean isLoopbackUrl(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }

        try {
            return isLoopbackHost(URI.create(uri).getHost());
        } catch (SecurityException | IllegalArgumentException e) {
            // exception is not important, its just hint to return false so logging is omitted
            return false;
        }
    }

    @VisibleForTesting
    ConfigurationUtil getConfigUtil() {
        return ConfigurationUtil.getInstance();
    }

    @VisibleForTesting
    OpenAPI getOpenAPI() {
        return OpenApiDocument.INSTANCE.get();
    }

    @Override
    public void destroy() {
    }
}