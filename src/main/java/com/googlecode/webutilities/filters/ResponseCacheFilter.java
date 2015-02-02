/*
 * Copyright 2010-2014 Rajendra Patil
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.webutilities.filters;

import com.googlecode.webutilities.common.Constants;
import com.googlecode.webutilities.common.WebUtilitiesResponseWrapper;
import com.googlecode.webutilities.common.cache.Cache;
import com.googlecode.webutilities.common.cache.CacheConfig;
import com.googlecode.webutilities.common.cache.CacheFactory;
import com.googlecode.webutilities.common.cache.CachedResponse;
import com.googlecode.webutilities.filters.common.AbstractFilter;
import com.googlecode.webutilities.servlets.JSCSSMergeServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

import static com.googlecode.webutilities.common.Constants.DEFAULT_CACHE_CONTROL;
import static com.googlecode.webutilities.common.Constants.DEFAULT_EXPIRES_MINUTES;
import static com.googlecode.webutilities.util.Utils.*;


/**
 * The <code>ResponseCacheFilter</code> is implemented as Servlet Filter to enable caching of STATIC resources (JS, CSS, static HTML files)
 * <p>
 * This enables the server side caching of the static resources, where client caching is done using JSCSSMergeServlet by setting
 * appropriate expires/Cache-Control headers.
 * </p>
 * <h3>Usage</h3>
 * <p>
 * Put the <b>webutilities-x.y.z.jar</b> in your classpath (WEB-INF/lib folder of your webapp).
 * </p>
 * <p>
 * Declare this filter in your <code>web.xml</code> ( web descriptor file)
 * </p>
 * <pre>
 * ...
 * &lt;filter&gt;
 * 	&lt;filter-name&gt;responseCacheFilter&lt;/filter-name&gt;</b>
 * 	&lt;filter-class&gt;<b>com.googlecode.webutilities.filters.ResponseCacheFilter</b>&lt;/filter-class&gt;
 * &lt;/filter&gt;
 * ...
 * </pre>
 * Map this filter on your JS and CSS resources
 * <pre>
 * ...
 * &lt;filter-mapping&gt;
 *   &lt;filter-name&gt;responseCacheFilter&lt;/filter-name&gt;
 *   &lt;url-pattern&gt;<b>*.js</b>&lt;/url-pattern&gt;
 *   &lt;url-pattern&gt;<b>*.json</b>&lt;/url-pattern&gt;
 *   &lt;url-pattern&gt;<b>*.css</b>&lt;/url-pattern&gt;
 * &lt;/filter-mapping>
 * ...
 * </pre>
 * <p>
 * And you are all done!
 * </p>
 * <p/>    `
 * Visit http://code.google.com/p/webutilities/wiki/ResponseCacheFilter for more details.
 *
 * @author rpatil
 * @version 1.0
 */

public class ResponseCacheFilter extends AbstractFilter {

    public static final String CACHE_HEADER = "X-ResponseCacheFilter";

    public static enum CacheState {FOUND, NOT_FOUND, ADDED, SKIPPED};

    private Cache<String, CachedResponse> cache;

    private int resetTime = 0;

    private long lastResetTime;

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseCacheFilter.class.getName());

    private static final String INIT_PARAM_CACHE_PROVIDER = "cacheProvider"; //Enum value {DEFAULT, MEMCACHED, REDIS};

    private static final String INIT_PARAM_CACHE_HOST = "cacheHost"; // Host for distributed cache

    private static final String INIT_PARAM_CACHE_PORT = "cachePort"; //Port for distributed cache

    private static final String INIT_PARAM_RELOAD_TIME = "reloadTime";

    private static final String INIT_PARAM_RESET_TIME = "resetTime";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);

        int reloadTime = readInt(filterConfig.getInitParameter(INIT_PARAM_RELOAD_TIME), 0);

        this.resetTime = readInt(filterConfig.getInitParameter(INIT_PARAM_RESET_TIME), resetTime);

        lastResetTime = new Date().getTime();

        CacheConfig<String, CachedResponse> cacheConfig = new CacheConfig<String, CachedResponse>();

        String providerValue = readString(filterConfig.getInitParameter(INIT_PARAM_CACHE_PROVIDER), null);

        if(providerValue != null)
          cacheConfig.setProvider(CacheConfig.CacheProvider.valueOf(providerValue));

        String cacheHost = filterConfig.getInitParameter(INIT_PARAM_CACHE_HOST);
        cacheConfig.setHostname(cacheHost);
        int cachePort = readInt(filterConfig.getInitParameter(INIT_PARAM_CACHE_PORT), 0);
        cacheConfig.setPortNumber(cachePort);

        if (!CacheFactory.isCacheProvider(cache, cacheConfig.getProvider())) {
          try {
            cache = CacheFactory.getCache(cacheConfig);
          } catch (Exception ex) {
            LOGGER.debug("Failed to initialize Cache Config: {}. Falling back to default Cache Service.", cacheConfig);
            cache = CacheFactory.<String, CachedResponse>getDefaultCache();
          }
        }

        LOGGER.debug("Cache Filter initialized with: " +
                "{}:{},\n" +
                "{}:{},\n" +
                "{}:{},\n" +
                "{}:{},\n" +
                "{}:{}",
                INIT_PARAM_CACHE_PROVIDER, providerValue,
                INIT_PARAM_CACHE_HOST, cacheHost,
                INIT_PARAM_CACHE_PORT, String.valueOf(cachePort),
                INIT_PARAM_RELOAD_TIME, String.valueOf(reloadTime),
                INIT_PARAM_RESET_TIME, String.valueOf(resetTime));
    }

    public Cache<String, CachedResponse> getCache() {
      return cache;
    }

    public void setCache(Cache<String, CachedResponse> cache) {
      this.cache = cache;
    }

  @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;

        String url = httpServletRequest.getRequestURI();

        httpServletResponse.setHeader(CACHE_HEADER, CacheState.SKIPPED.toString());

        if (!isURLAccepted(url)
                || !isQueryStringAccepted(httpServletRequest.getQueryString())
                || !isUserAgentAccepted(httpServletRequest.getHeader(Constants.HTTP_USER_AGENT_HEADER))) {
            LOGGER.debug("Skipping Cache filter for: {}?{}", url, httpServletRequest.getQueryString());
            LOGGER.debug("URL, QueryString or UserAgent not accepted");
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }


        long now = new Date().getTime();

      CachedResponse cachedResponse = cache.get(url);

        boolean expireCache = httpServletRequest.getParameter(Constants.PARAM_EXPIRE_CACHE) != null;

        if (expireCache) {
            LOGGER.trace("Removing Cache for {}  due to URL parameter.", url);
            cache.invalidate(url);
        }

        boolean resetCache = httpServletRequest.getParameter(Constants.PARAM_RESET_CACHE) != null ||
                resetTime > 0 && (now - lastResetTime) / 1000 > resetTime;

        if (resetCache) {
            LOGGER.trace("Resetting whole Cache for {} due to URL parameter.", url);
            cache.invalidateAll(); // fixme: we don't need reset since cache values are soft referenced.
            lastResetTime = now;
        }

        boolean skipCache = httpServletRequest.getParameter(Constants.PARAM_DEBUG) != null || httpServletRequest.getParameter(Constants.PARAM_SKIP_CACHE) != null;

        if (skipCache) {
            filterChain.doFilter(servletRequest, servletResponse);
            LOGGER.trace("Skipping Cache for {} due to URL parameter.", url);
            return;
        }

        List<String> requestedResources = findResourcesToMerge(httpServletRequest.getContextPath(), url);
        ServletContext context = filterConfig.getServletContext();
        String extensionOrPath = detectExtension(url);//in case of non js/css files it null
        if (extensionOrPath == null) {
            extensionOrPath = requestedResources.get(0);//non grouped i.e. non css/js file, we refer it's path in that case
        }

        JSCSSMergeServlet.ResourceStatus status = JSCSSMergeServlet.isNotModified(context, httpServletRequest, requestedResources, false);
        if (status.isNotModified()) {
            LOGGER.trace("Resources Not Modified. Sending 304.");
            cache.invalidate(url);
            JSCSSMergeServlet.sendNotModified(httpServletResponse, extensionOrPath, status.getActualETag(), DEFAULT_EXPIRES_MINUTES, DEFAULT_CACHE_CONTROL);
            return;
        }

        boolean cacheFound = false;

        if (cachedResponse != null) {
            if (requestedResources != null && isAnyResourceModifiedSince(requestedResources, cachedResponse.getTime(), context)) {
                LOGGER.trace("Some resources have been modified since last cache: {}", url);
                cache.invalidate(url);
                cacheFound = false;
            } else {
                LOGGER.trace("Found valid cached response.");
                //cacheObject.increaseAccessCount();
                cacheFound = true;
            }
        }

        if (cacheFound) {
            LOGGER.debug("Returning Cached response.");
            cachedResponse.toResponse(httpServletResponse);
            httpServletResponse.setHeader(CACHE_HEADER, CacheState.FOUND.toString());
        } else {
            LOGGER.trace("Cache not found or invalidated");
            httpServletResponse.setHeader(CACHE_HEADER, CacheState.NOT_FOUND.toString());
            WebUtilitiesResponseWrapper wrapper = new WebUtilitiesResponseWrapper(httpServletResponse);
            filterChain.doFilter(servletRequest, wrapper);

            // some filters return no status code, but we believe that it is "200 OK"
            if (wrapper.getStatus() == 0) {
              wrapper.setStatus(200);
            }
            if (isMIMEAccepted(wrapper.getContentType()) && !expireCache && !resetCache && wrapper.getStatus() == 200) { //Cache only 200 status response
                cache.put(url, new CachedResponse(getLastModifiedFor(requestedResources, context), wrapper));
                LOGGER.debug("Cache added for: {}", url);
                httpServletResponse.setHeader(CACHE_HEADER, CacheState.ADDED.toString());
            } else {
                LOGGER.trace("Cache NOT added for: {}", url);
                LOGGER.trace("is MIME not accepted: {}", isMIMEAccepted(wrapper.getContentType()));
                LOGGER.trace("is expireCache: {}", expireCache);
                LOGGER.trace("is resetCache: {}", resetCache);
            }
            wrapper.fill(httpServletResponse);
        }

    }
}





