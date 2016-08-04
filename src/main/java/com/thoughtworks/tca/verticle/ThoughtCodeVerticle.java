package com.thoughtworks.tca.verticle;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ThoughtCodeVerticle extends AbstractVerticle {

    private final Logger LOG = Logger.getLogger(ThoughtCodeVerticle.class.getName());

    @Override
    public void start() {

        Router router = Router.router(vertx);
        Set<HttpMethod> allowedHTTPMethods = new HashSet<>();
        allowedHTTPMethods.add(HttpMethod.GET);
        allowedHTTPMethods.add(HttpMethod.POST);
        allowedHTTPMethods.add(HttpMethod.PUT);
        allowedHTTPMethods.add(HttpMethod.DELETE);
        router.route().handler(CorsHandler.create("*").allowedMethods(allowedHTTPMethods));

        router.route().handler(BodyHandler.create());
        router.post("/api/v1/questions").handler(this::handleAddQuestion);
        router.patch("/api/v1/questions/:questionID").handler(this::handleUpdateQuestion);
        router.delete("/api/v1/questions/:questionID").handler(this::handleDeleteQuestion);
        router.get("/api/v1/questions").handler(this::handleListQuestions);


        vertx.createHttpServer(new HttpServerOptions().setCompressionSupported(true))
                .requestHandler(router::accept)
                .listen(Integer.getInteger("http.port"), System.getProperty("http.address", "0.0.0.0"));

    }

    private JDBCClient jdbcClient()
    {
        return JDBCClient.createShared(vertx, new JsonObject()
                .put("url", System.getenv("JDBC_DATABASE_URL")));
    }

    private void handleAddQuestion(RoutingContext routingContext) {
        JsonObject question = routingContext.getBodyAsJson();
        HttpServerResponse response = routingContext.response();
        if(question == null){
            sendError(400, response);
            return;
        }
        jdbcClient().getConnection(conn -> {
            if(conn.failed()){
                LOG.log(Level.SEVERE, "Unable to get DB connection.");
                sendError(500, response);
            }else{
                final SQLConnection connection = conn.result();
                String query = "insert into question(description_url, coding_round) values(?, ?)";
                JsonArray params = new JsonArray()
                        .add(question.getValue("descriptionUrl"))
                        .add(question.getValue("codingRound"));
                connection.updateWithParams(query, params, res -> {
                    if(res.failed()){
                        LOG.log(Level.SEVERE, res.cause().getMessage());
                        sendError(500, response);
                    }else{
                        response.end();
                    }
                    connection.close();
                });
            }
        });
    }

    private void handleUpdateQuestion(RoutingContext routingContext) {
        String questionID = routingContext.request().getParam("questionID");
        HttpServerResponse response = routingContext.response();
        if (questionID == null) {
            sendError(400, response);
        } else {
            JsonObject question = routingContext.getBodyAsJson();
            if (question == null) {
                sendError(400, response);
            } else {
                jdbcClient().getConnection(conn -> {
                    if(conn.failed()){
                        LOG.log(Level.SEVERE, "Unable to get DB connection.");
                        sendError(500, response);
                    }else{
                        final SQLConnection connection = conn.result();
                        String query = "update question set where_asked = ? where qid = ?";
                        JsonArray params = new JsonArray()
                                .add(question.getValue("whereAsked"))
                                .add(Integer.parseInt(questionID));
                        connection.updateWithParams(query, params, res -> {
                            if(res.failed()){
                                LOG.log(Level.SEVERE, res.cause().getMessage());
                                sendError(500, response);
                            }else{
                                response.end();
                            }
                            connection.close();
                        });
                    }
                });
            }
        }
    }

    private void handleDeleteQuestion(RoutingContext routingContext) {
        String questionID = routingContext.request().getParam("questionID");
        HttpServerResponse response = routingContext.response();
        if (questionID == null) {
            sendError(400, response);
        } else {
            jdbcClient().getConnection(conn -> {
                if (conn.failed()) {
                    LOG.log(Level.SEVERE, "Unable to get DB connection.");
                    sendError(500, response);
                } else {
                    final SQLConnection connection = conn.result();
                    String query = "delete from question where qid = ?";
                    JsonArray params = new JsonArray()
                            .add(Integer.parseInt(questionID));
                    connection.updateWithParams(query, params, res -> {
                        if (res.failed()) {
                            LOG.log(Level.SEVERE, res.cause().getMessage());
                            sendError(500, response);
                        } else {
                            response.end();
                        }
                        connection.close();
                    });
                }
            });
        }
    }

    private void handleListQuestions(RoutingContext routingContext) {
        jdbcClient().getConnection(conn -> {
            if (conn.failed()) {
                LOG.log(Level.SEVERE, "Unable to get DB connection");
            } else {
                final SQLConnection connection = conn.result();
                String query = "select description_url, coding_round, where_asked from question order by where_asked";
                connection.query(query, res -> {
                    if(res.failed()){
                        LOG.log(Level.SEVERE, "Unable to fetch query result");
                        connection.close();
                    }else{
                        ResultSet rs = res.result();
                        List<JsonArray> data = rs.getResults();
                        LOG.log(Level.INFO, "Data size " + ((data == null)? "null":data.size()));
                        JsonArray returnArray = new JsonArray();
                        data.forEach(row -> {
                            JsonObject jsonObject = new JsonObject();
                            jsonObject.put("descriptionURL", row.getValue(0));
                            vertx.createHttpClient(new HttpClientOptions().setSsl(true)).getNow("script.google.com", "/macros/s/AKfycbwRVDKt5ApSadrc04rBUEugnWxNmY6iHpMgLxScBSamPmHmCzxl/exec?docUrl=" + row.getValue(0), response -> {
                                LOG.log(Level.INFO, "Received response " + response.statusCode());

                                response.bodyHandler(body -> {
                                    LOG.log(Level.INFO, body.toString());
                                });
                            });
                            jsonObject.put("codingRound", row.getValue(1));
                            jsonObject.put("whereAsked", row.getValue(2));
                            returnArray.add(jsonObject);
                        });
                        connection.close();
                        LOG.log(Level.INFO, returnArray.size() + " questions returned ");
                        routingContext.response().putHeader("content-type", "application/json").end(returnArray.encode());
                    }
                });
            }
        });
    }

    private void sendError(int statusCode, HttpServerResponse response) {
        response.setStatusCode(statusCode).end();
    }

}
