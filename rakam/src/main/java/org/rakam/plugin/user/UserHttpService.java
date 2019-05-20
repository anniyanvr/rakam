package org.rakam.plugin.user;

import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.cookie.Cookie;
import org.rakam.analysis.ApiKeyService;
import org.rakam.analysis.RequestContext;
import org.rakam.collection.EventCollectionHttpService;
import org.rakam.collection.EventCollectionHttpService.HttpRequestParams;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.user.AbstractUserService.BatchUserOperationRequest;
import org.rakam.plugin.user.AbstractUserService.BatchUserOperationRequest.BatchUserOperations;
import org.rakam.plugin.user.AbstractUserService.SingleUserBatchOperationRequest;
import org.rakam.server.http.HttpRequestException;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.server.http.annotations.*;
import org.rakam.util.*;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.google.common.base.Charsets.UTF_8;
import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.cookie.ServerCookieEncoder.STRICT;
import static java.lang.String.format;
import static org.rakam.analysis.ApiKeyService.AccessKeyType.MASTER_KEY;
import static org.rakam.analysis.ApiKeyService.AccessKeyType.WRITE_KEY;
import static org.rakam.collection.EventCollectionHttpService.getHeaderList;
import static org.rakam.collection.EventCollectionHttpService.setBrowser;
import static org.rakam.server.http.HttpServer.errorMessage;
import static org.rakam.server.http.HttpServer.returnError;

@Path("/user")
@Api(value = "/user", nickname = "user", description = "User", tags = "user")
public class UserHttpService
        extends HttpService {
    private final static Logger LOGGER = Logger.get(UserHttpService.class);
    private final static SqlParser sqlParser = new SqlParser();
    private final byte[] OK_MESSAGE = "1".getBytes(UTF_8);
    private final UserPluginConfig config;
    private final AbstractUserService service;
    private final Set<UserPropertyMapper> mappers;
    private final ApiKeyService apiKeyService;

    @Inject
    public UserHttpService(UserPluginConfig config,
                           Set<UserPropertyMapper> mappers,
                           ApiKeyService apiKeyService,
                           AbstractUserService service) {
        this.service = service;
        this.config = config;
        this.apiKeyService = apiKeyService;
        this.mappers = mappers;
    }

    public static Expression parseExpression(String filter) {
        if (filter != null) {
            try {
                synchronized (sqlParser) {
                    return sqlParser.createExpression(filter);
                }
            } catch (Exception e) {
                throw new RakamException(format("filter expression '%s' couldn't parsed", filter),
                        BAD_REQUEST);
            }
        } else {
            return null;
        }
    }

    @GET
    @ApiOperation(value = "Get user storage metadata", authorizations = @Authorization(value = "read_key"))
    @JsonRequest
    @Path("/metadata")
    public MetadataResponse getMetadata(@Named("project") RequestContext context) {
        return new MetadataResponse(config.getIdentifierColumn(), service.getMetadata(context));
    }

    @ApiOperation(value = "Batch operation on a single user properties",
            request = SingleUserBatchOperationRequest.class, response = Integer.class,
            authorizations = {@Authorization(value = "write_key")})
    @ApiResponses(value = {@ApiResponse(code = 404, message = "User does not exist.")})
    @Path("/batch")
    @JsonRequest
    public void batchSingleUserOperations(RakamHttpRequest request) {
        request.bodyHandler(s -> {
            SingleUserBatchOperationRequest req;
            try {
                req = JsonHelper.read(s, SingleUserBatchOperationRequest.class);
            } catch (Exception e) {
                returnError(request, e.getMessage(), BAD_REQUEST);
                return;
            }

            String project = apiKeyService.getProjectOfApiKey(req.api.apiKey, WRITE_KEY);

            InetAddress socketAddress = ((InetSocketAddress) request.context().channel()
                    .remoteAddress()).getAddress();

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, wrappedBuffer(OK_MESSAGE));
            List<Cookie> cookies = mapEvent(mapper ->
                    mapper.map(project, req.data, new HttpRequestParams(request), socketAddress));

            service.batch(project, req.data);

            setBrowser(request, response);
            if (cookies != null && !cookies.isEmpty()) {
                response.headers().add(SET_COOKIE, STRICT.encode(cookies));
            }
            request.response(response).end();
        });
    }

    @ApiOperation(value = "Batch operations on user properties", request = BatchUserOperationRequest.class,
            response = Integer.class, authorizations = @Authorization(value = "master_key"))
    @ApiResponses(value = {@ApiResponse(code = 404, message = "User does not exist.")})
    @Path("/batch_operations")
    @JsonRequest
    public void batchUserOperations(RakamHttpRequest request) {
        request.bodyHandler(s -> {
            BatchUserOperationRequest req;
            try {
                req = JsonHelper.read(s, BatchUserOperationRequest.class);
            } catch (Exception e) {
                LogUtil.logException(request, e);
                returnError(request, e.getMessage(), BAD_REQUEST);
                return;
            }

            String project = apiKeyService.getProjectOfApiKey(req.api.apiKey, MASTER_KEY);

            InetAddress socketAddress = ((InetSocketAddress) request.context().channel()
                    .remoteAddress()).getAddress();

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, wrappedBuffer(OK_MESSAGE));
            List<Cookie> cookies = mapEvent(mapper ->
                    mapper.map(project, req.data, new HttpRequestParams(request), socketAddress));

            service.batch(project, req.data).whenComplete((result, ex) -> {
                setBrowser(request, response);

                if (ex != null) {
                    request.response(JsonHelper.encode(errorMessage("An error occurred", INTERNAL_SERVER_ERROR)),
                            INTERNAL_SERVER_ERROR);
                    LOGGER.error(ex, "Error while performing batch user operation");
                    return;
                }

                if (cookies != null && !cookies.isEmpty()) {
                    response.headers().add(SET_COOKIE, STRICT.encode(cookies));
                }
                request.response(response).end();
            });
        });
    }

    public List<Cookie> mapEvent(Function<UserPropertyMapper, List<Cookie>> mapperFunction) {
        List<Cookie> cookies = null;
        for (UserPropertyMapper mapper : mappers) {
            // TODO: bound event mappers to Netty Channels and runStatementSafe them in separate thread
            List<Cookie> mapperCookies = mapperFunction.apply(mapper);
            if (mapperCookies != null) {
                if (cookies == null) {
                    cookies = new ArrayList<>();
                }
                cookies.addAll(mapperCookies);
            }
        }

        return cookies;
    }

    @ApiOperation(value = "Set user properties", request = User.class, response = Integer.class)
    @ApiResponses(value = {@ApiResponse(code = 404, message = "User does not exist.")})
    @Path("/set_properties")
    @POST
    public void setProperties(RakamHttpRequest request) {
        setPropertiesInline(request, (project, user) -> service.setUserProperties(project, user.id, user.properties));
    }

    public void setPropertiesInline(RakamHttpRequest request, BiConsumer<String, User> mapper) {
        request.bodyHandler(s -> {
            User req;
            try {
                req = JsonHelper.readSafe(s, User.class);
            } catch (IOException e) {
                returnError(request, e.getMessage(), BAD_REQUEST);
                return;
            }

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK,
                    wrappedBuffer(OK_MESSAGE));
            setBrowser(request, response);

            try {
                String project = apiKeyService.getProjectOfApiKey(req.api.apiKey, WRITE_KEY);

                List<Cookie> cookies = mapProperties(project, req, request);
                if (cookies != null) {
                    response.headers().add(SET_COOKIE, STRICT.encode(cookies));
                }
                String headerList = getHeaderList(response.headers().iterator());
                if (headerList != null) {
                    response.headers().set(ACCESS_CONTROL_EXPOSE_HEADERS, headerList);
                }

                mapper.accept(project, req);
                request.response(response).end();
            } catch (RakamException e) {
                LogUtil.logException(request, e);
                EventCollectionHttpService.returnError(request, e.getMessage(), e.getStatusCode());
            } catch (HttpRequestException e) {
                EventCollectionHttpService.returnError(request, e.getMessage(), e.getStatusCode());
            } catch (Throwable t) {
                LOGGER.error(t);
                EventCollectionHttpService.returnError(request, "An error occurred", INTERNAL_SERVER_ERROR);
            }
        });
    }

    private List<Cookie> mapProperties(String project, User req, RakamHttpRequest request) {
        InetAddress socketAddress = ((InetSocketAddress) request.context().channel()
                .remoteAddress()).getAddress();

        List<Cookie> cookies = null;
        BatchUserOperationRequest op = new BatchUserOperationRequest(req.api,
                ImmutableList.of(new BatchUserOperations(req.id, req.properties, null, null, null, null)));

        for (UserPropertyMapper mapper : mappers) {
            try {
                List<Cookie> map = mapper.map(project, op.data, new HttpRequestParams(request), socketAddress);
                if (map != null) {
                    if (cookies == null) {
                        cookies = new ArrayList<>();
                    }

                    cookies.addAll(map);
                }
            } catch (Exception e) {
                LOGGER.error(e, "Error while mapping user properties in " + mapper.getClass().toString());
                return null;
            }
        }

        return cookies;
    }

    @JsonRequest
    @ApiOperation(value = "Set user properties once", request = User.class, response = Integer.class)
    @ApiResponses(value = {@ApiResponse(code = 404, message = "User does not exist.")})
    @Path("/set_properties_once")
    public void setPropertiesOnce(RakamHttpRequest request) {
        request.bodyHandler(s -> {
            User req;
            try {
                req = JsonHelper.readSafe(s, User.class);
            } catch (IOException e) {
                returnError(request, e.getMessage(), BAD_REQUEST);
                return;
            }

            String project = apiKeyService.getProjectOfApiKey(req.api.apiKey, WRITE_KEY);

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, wrappedBuffer(OK_MESSAGE));
            response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, request.headers().get(ORIGIN));

            List<Cookie> cookies = mapProperties(project, req, request);
            if (cookies != null) {
                response.headers().add(SET_COOKIE,
                        STRICT.encode(cookies));
            }
            String headerList = getHeaderList(response.headers().iterator());
            if (headerList != null) {
                response.headers().set(ACCESS_CONTROL_EXPOSE_HEADERS, headerList);
            }

            // TODO: we may cache these values and reduce the db hit.
            service.setUserPropertiesOnce(project, req.id, req.properties);
            request.response(OK_MESSAGE).end();
        });
    }

    @JsonRequest
    @ApiOperation(value = "Set user property", authorizations = @Authorization(value = "master_key"))
    @ApiResponses(value = {@ApiResponse(code = 404, message = "User does not exist.")})
    @Path("/increment_property")
    @AllowCookie
    public SuccessMessage incrementProperty(@ApiParam("api") User.UserContext api,
                                            @ApiParam("id") String user,
                                            @ApiParam("property") String property,
                                            @ApiParam("value") double value) {
        String project = apiKeyService.getProjectOfApiKey(api.apiKey, WRITE_KEY);
        service.incrementProperty(project, user, property, value);
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Unset user property")
    @ApiResponses(value = {@ApiResponse(code = 404, message = "User does not exist.")})
    @Path("/unset_properties")
    @AllowCookie
    public SuccessMessage unsetProperty(@ApiParam("api") User.UserContext api,
                                        @ApiParam("id") Object id,
                                        @ApiParam("properties") List<String> properties) {
        String project = apiKeyService.getProjectOfApiKey(api.apiKey, WRITE_KEY);
        service.unsetProperties(project, id, properties);
        return SuccessMessage.success();
    }

    public static class MetadataResponse {
        public final List<SchemaField> columns;
        public final String identifierColumn;

        public MetadataResponse(String identifierColumn, List<SchemaField> columns) {
            this.identifierColumn = identifierColumn;
            this.columns = columns;
        }
    }
}
