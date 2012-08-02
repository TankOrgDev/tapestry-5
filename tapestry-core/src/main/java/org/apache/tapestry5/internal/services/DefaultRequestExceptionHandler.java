// Copyright 2006, 2008, 2010, 2011, 2012 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.internal.services;

import org.apache.tapestry5.ContextAwareException;
import org.apache.tapestry5.ExceptionHandlerAssistant;
import org.apache.tapestry5.Link;
import org.apache.tapestry5.SymbolConstants;
import org.apache.tapestry5.internal.InternalConstants;
import org.apache.tapestry5.internal.structure.Page;
import org.apache.tapestry5.ioc.ServiceResources;
import org.apache.tapestry5.ioc.annotations.Symbol;
import org.apache.tapestry5.ioc.internal.OperationException;
import org.apache.tapestry5.ioc.internal.util.InternalUtils;
import org.apache.tapestry5.json.JSONObject;
import org.apache.tapestry5.runtime.ComponentEventException;
import org.apache.tapestry5.services.*;
import org.slf4j.Logger;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Default implementation of {@link RequestExceptionHandler} that displays the standard ExceptionReport page. The page
 * must implement the {@link ExceptionReporter} interface.
 */
public class DefaultRequestExceptionHandler implements RequestExceptionHandler
{
    private final RequestPageCache pageCache;

    private final PageResponseRenderer renderer;

    private final Logger logger;

    private final String pageName;

    private final Request request;

    private final Response response;

    private final ComponentClassResolver componentClassResolver;

    private final LinkSource linkSource;

    // should be Class<? extends Throwable>, Object but it's not allowed to configure subtypes
    private final Map<Class, Object> configuration;

    @SuppressWarnings("rawtypes")
    public DefaultRequestExceptionHandler(RequestPageCache pageCache, PageResponseRenderer renderer, Logger logger,

                                          @Symbol(SymbolConstants.EXCEPTION_REPORT_PAGE)
                                          String pageName,

                                          Request request, Response response, ComponentClassResolver componentClassResolver, LinkSource linkSource, ServiceResources serviceResources, Map<Class, Object> configuration)
    {
        this.pageCache = pageCache;
        this.renderer = renderer;
        this.logger = logger;
        this.pageName = pageName;
        this.request = request;
        this.response = response;
        this.componentClassResolver = componentClassResolver;
        this.linkSource = linkSource;

        Map<Class<ExceptionHandlerAssistant>, ExceptionHandlerAssistant> handlerAssistants = new HashMap<Class<ExceptionHandlerAssistant>, ExceptionHandlerAssistant>();

        for (Entry<Class, Object> entry : configuration.entrySet())
        {
            if (!Throwable.class.isAssignableFrom(entry.getKey()))
                throw new IllegalArgumentException(Throwable.class.getName() + " is the only allowable key type but " + entry.getKey().getName()
                        + " was contributed");

            if (ExceptionHandlerAssistant.class.isAssignableFrom((Class) entry.getValue()))
            {
                @SuppressWarnings("unchecked")
                Class<ExceptionHandlerAssistant> handlerType = (Class<ExceptionHandlerAssistant>) entry.getValue();
                ExceptionHandlerAssistant assistant = handlerAssistants.get(handlerType);
                if (assistant == null)
                {
                    assistant = (ExceptionHandlerAssistant) serviceResources.autobuild(handlerType);
                    handlerAssistants.put(handlerType, assistant);
                }
                entry.setValue(assistant);
            }
        }
        this.configuration = configuration;
    }

    public void handleRequestException(Throwable exception) throws IOException
    {
        // skip handling of known exceptions if there are none configured 
        if (configuration.isEmpty())
        {
            renderException(exception);
            return;
        }

        Throwable cause = exception;

        // Depending on where the error was thrown, there could be several levels of wrappers..
        // For exceptions in component operations, it's OperationException -> ComponentEventException -> <Target>Exception

        // Throw away the wrapped exceptions first
        while (cause instanceof OperationException || cause instanceof ComponentEventException)
        {
            if (cause.getCause() == null) break;
            cause = cause.getCause();
        }

        Class<?> causeClass = cause.getClass();
        if (!configuration.containsKey(causeClass))
        {
            // try at most two level of superclasses before delegating back to the default exception handler
            causeClass = causeClass.getSuperclass();
            if (causeClass == null || !configuration.containsKey(causeClass))
            {
                causeClass = causeClass.getSuperclass();
                if (causeClass == null || !configuration.containsKey(causeClass))
                {
                    renderException(exception);
                    return;
                }
            }
        }

        Object[] exceptionContext = formExceptionContext(cause);
        Object value = configuration.get(causeClass);
        Object page = null;
        ExceptionHandlerAssistant assistant = null;
        if (value instanceof ExceptionHandlerAssistant)
        {
            assistant = (ExceptionHandlerAssistant) value;
            // in case the assistant changes the context
            List context = Arrays.asList(exceptionContext);
            page = assistant.handleRequestException(exception, context);
            exceptionContext = context.toArray();
        } else if (!(value instanceof Class))
        {
            renderException(exception);
            return;
        } else page = value;

        if (page == null) return;

        try
        {
            if (page instanceof Class)
                page = componentClassResolver.resolvePageClassNameToPageName(((Class) page).getName());

            Link link = page instanceof Link
                    ? (Link) page
                    : linkSource.createPageRenderLink(page.toString(), false, exceptionContext);

            if (request.isXHR())
            {
                OutputStream os = response.getOutputStream("application/json;charset=UTF-8");

                JSONObject reply = new JSONObject();
                reply.in(InternalConstants.PARTIAL_KEY).put("redirectURL", link.toAbsoluteURI());

                os.write(reply.toCompactString().getBytes("UTF-8"));

                os.close();

                return;
            }

            // Normal behavior is just a redirect.

            response.sendRedirect(link);
        }
        // The above could throw an exception if we are already on a render request, but it's
        // user's responsibility not to abuse the mechanism
        catch (Exception e)
        {
            // Nothing to do but delegate
            renderException(exception);
        }
    }

    private void renderException(Throwable exception) throws IOException
    {
        logger.error(String.format("Processing of request failed with uncaught exception: %s", exception), exception);

        // TAP5-233: Make sure the client knows that an error occurred.

        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

        String rawMessage = InternalUtils.toMessage(exception);

        // Encode it compatibly with the JavaScript escape() function.

        String encoded = URLEncoder.encode(rawMessage, "UTF-8").replace("+", "%20");

        response.setHeader("X-Tapestry-ErrorMessage", encoded);

        Page page = pageCache.get(pageName);

        ExceptionReporter rootComponent = (ExceptionReporter) page.getRootComponent();

        // Let the page set up for the new exception.

        rootComponent.reportException(exception);

        renderer.renderPageResponse(page);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Object[] formExceptionContext(Throwable exception)
    {
        if (exception instanceof ContextAwareException) return ((ContextAwareException) exception).getContext();

        Class exceptionClass = exception.getClass();
        // pick the first class in the hierarchy that's not anonymous, probably no reason check for array types
        while ("".equals(exceptionClass.getSimpleName()))
            exceptionClass = exceptionClass.getSuperclass();

        // check if exception type is plain runtimeException - yes, we really want the test to be this way
        if (exceptionClass.isAssignableFrom(RuntimeException.class))
            return exception.getMessage() == null ? new Object[0] : new Object[]{exception.getMessage().toLowerCase()};

        // otherwise, form the context from the exception type name
        String exceptionType = exceptionClass.getSimpleName();
        if (exceptionType.endsWith("Exception")) exceptionType = exceptionType.substring(0, exceptionType.length() - 9);
        return new Object[]{exceptionType.toLowerCase()};
    }

}
