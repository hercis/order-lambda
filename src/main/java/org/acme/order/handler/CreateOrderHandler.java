package org.acme.order.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.order.api.CreateOrderRequest;
import org.acme.order.domain.Order;
import org.acme.order.service.OrderService;
import org.acme.support.AppError;
import org.acme.support.AppError.InternalAppError;
import org.acme.support.AppError.NotFoundError;
import org.acme.support.AppError.ValidationError;
import org.acme.support.AppResponse;
import org.acme.support.Result;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

// import jakarta.json.Json;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.http.SdkHttpMethod;

@Named("createOrder")
public class CreateOrderHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Inject ObjectMapper mapper;
  @Inject Validator validator;
  @Inject OrderService service;

  @ConfigProperty(name = "order.cors-origin")
  private String corsOrigin;

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    if (event.getHttpMethod().equals(SdkHttpMethod.OPTIONS.name())) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(HttpStatusCode.OK)
          .withHeaders(corsHeaders());
    }
    if (!event.getHttpMethod().equals(SdkHttpMethod.POST.name())) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(HttpStatusCode.METHOD_NOT_ALLOWED)
          .withBody("Only POST method is supported");
    }

    try {
      CreateOrderRequest request = mapper.readValue(event.getBody(), CreateOrderRequest.class);

      Set<ConstraintViolation<Object>> violations = validator.validate(request);
      if (!violations.isEmpty()) {
        List<ValidationError> errors =
            violations.stream()
                .map(v -> ValidationError.from(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withBody(buildBody(AppResponse.fromErrors("Validation failed", errors)))
            .withHeaders(corsHeaders(Map.of("Content-Type", "application/json")));
      }

      Result<Order, AppError> serviceResult = service.create(request);
      Result<String, AppError> mappingOutput =
          serviceResult.flatMap(
              order ->
                  Result.tryOf(() -> mapper.writeValueAsString(order))
                      .mapError(ex -> InternalAppError.fromCause("json", ex)));

      APIGatewayProxyResponseEvent response =
          mappingOutput.fold(
              error ->
                  switch (error) {
                    case NotFoundError e ->
                        new APIGatewayProxyResponseEvent()
                            .withStatusCode(HttpStatusCode.NOT_FOUND)
                            .withBody(buildBody(e))
                            .withHeaders(corsHeaders(Map.of("Content-Type", "application/json")));
                    case AppError e ->
                        new APIGatewayProxyResponseEvent()
                            .withStatusCode(HttpStatusCode.INTERNAL_SERVER_ERROR)
                            .withBody(buildBody(e))
                            .withHeaders(corsHeaders(Map.of("Content-Type", "application/json")));
                  },
              jsonStr ->
                  new APIGatewayProxyResponseEvent()
                      .withStatusCode(HttpStatusCode.CREATED)
                      .withBody(jsonStr)
                      .withHeaders(corsHeaders(Map.of("Content-Type", "application/json"))));
      return response;
    } catch (Exception e) {
      context.getLogger().log(e.getMessage());
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(HttpStatusCode.INTERNAL_SERVER_ERROR)
          .withHeaders(corsHeaders(Map.of("Content-Type", "application/json")))
          .withBody(buildBody(AppResponse.fromMessage("Internal server error")));
    }
  }

  private String buildBody(AppResponse response) {
    ObjectNode root = mapper.createObjectNode().put("message", response.message());
    if (response.errors() != null) {
      root.set("errors", mapper.valueToTree(response.errors()));
    }
    return root.toString();
  }

  private String buildBody(AppError error) {
    return buildBody(AppResponse.fromError(error));
  }

  private Map<String, String> corsHeaders() {
    return corsHeaders(Map.of());
  }

  private Map<String, String> corsHeaders(Map<String, String> additionalHeaders) {
    Map<String, String> headers = new HashMap<>(additionalHeaders);
    headers.put("Access-Control-Allow-Origin", corsOrigin);
    headers.put("Access-Control-Allow-Methods", "OPTIONS,PUT");
    headers.put("Access-Control-Allow-Headers", "Content-Type,Accept");
    headers.put("Access-Control-Allow-Credentials", "true");
    return headers;
  }
}
