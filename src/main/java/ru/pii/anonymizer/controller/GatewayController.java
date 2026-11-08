package ru.pii.anonymizer.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ru.pii.anonymizer.dto.GatewayRequest;
import ru.pii.anonymizer.service.GatewayService;

/**
 * PII-шлюз для Claude Code: принимает всё, что не обработали остальные контроллеры ({@code /v1/messages} и др.),
 * и передаёт в {@link GatewayService}, который обезличивает запрос, пересылает в Anthropic и восстанавливает ответ.
 */
@RestController
public class GatewayController {

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        GatewayRequest incoming = new GatewayRequest(request.getMethod(), request.getRequestURI(),
                request.getQueryString(), headers, request.getInputStream().readAllBytes());

        gateway.forward(incoming, new GatewayService.Response() {
            @Override
            public void status(int status) {
                response.setStatus(status);
            }

            @Override
            public void header(String name, String value) {
                response.addHeader(name, value);
            }

            @Override
            public OutputStream body() throws IOException {
                return response.getOutputStream();
            }
        });
    }
}
