package com.foobnix.ai;

import android.content.Context;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Unified LLM access layer (the orchestration seed, Anx Reader style): the
 * protocol field routes to one of the wire formats while callers only deal
 * with plain messages. Covers OpenAI-compatible endpoints (OpenAI, DeepSeek,
 * OpenRouter, local gateways), Anthropic Claude and Google Gemini.
 *
 * The request body is always the minimal real call ("ping", max_tokens 5) so
 * a test connection verifies routing, auth and model name without cost.
 */
public class AiClient {

    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_GOOGLE = "google";

    public static final String DEFAULT_URL_OPENAI = "https://api.openai.com/v1";
    public static final String DEFAULT_URL_ANTHROPIC = "https://api.anthropic.com/v1";
    public static final String DEFAULT_URL_GOOGLE = "https://generativelanguage.googleapis.com/v1beta";

    /** Error kind of the last failed call: "", "auth", "rate", "timeout", "network", "model", "other". */
    public static volatile String lastError = "";

    public static boolean isOpenAiCompatible(String protocol) {
        return TxtUtils.isEmpty(protocol) || PROTOCOL_OPENAI.equals(protocol);
    }

    public static String defaultUrl(String protocol) {
        if (PROTOCOL_ANTHROPIC.equals(protocol)) {
            return DEFAULT_URL_ANTHROPIC;
        }
        if (PROTOCOL_GOOGLE.equals(protocol)) {
            return DEFAULT_URL_GOOGLE;
        }
        return DEFAULT_URL_OPENAI;
    }

    public static class TestResult {
        public boolean ok = false;
        /** "", "auth", "rate", "timeout", "network", "model", "no_config", "empty", "other" */
        public String error = "";
        /** Extra diagnostics: HTTP code and/or a response snippet for the UI. */
        public String detail = "";
        /** True when the model hit the output length cap (finish_reason=length). */
        public boolean truncated = false;
        /** First text produced by the model on success (proof of life). */
        public String reply = "";
    }

    /** Fire a minimal real chat request with the current persisted config. */
    public static TestResult testConnection(Context c) {
        String key = AiCredentials.load(c);
        String url = AppState.get().aiBaseUrl;
        String model = AppState.get().aiModel;
        if (TxtUtils.isEmpty(url) || TxtUtils.isEmpty(model) || TxtUtils.isEmpty(key)) {
            TestResult r = new TestResult();
            r.error = "no_config";
            return r;
        }
        return chat(c, AppState.get().aiProtocol, url, key, model, "ping", 5,
                AppState.get().aiThinking);
    }

    /** Ask with the current persisted config; the token budget is user-tunable.
     *  PRO feature hard gate: locked/fdroid builds never reach the network
     *  (defense in depth — every UI entry point is gated too). */
    public static TestResult ask(Context c, String userText) {
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            TestResult r = new TestResult();
            r.error = "pro_required";
            return r;
        }
        String key = AiCredentials.load(c);
        String url = AppState.get().aiBaseUrl;
        String model = AppState.get().aiModel;
        if (TxtUtils.isEmpty(url) || TxtUtils.isEmpty(model) || TxtUtils.isEmpty(key)) {
            TestResult r = new TestResult();
            r.error = "no_config";
            return r;
        }
        int budget = AppState.get().aiMaxTokens;
        if (budget <= 0) {
            budget = 4096;
        }
        return chat(c, AppState.get().aiProtocol, url, key, model, userText, budget,
                AppState.get().aiThinking);
    }

    /**
     * Fetch the provider's model list (OpenAI-compatible GET /models, Claude
     * GET /models, Gemini GET /models). Returns the model ids, or null when
     * the request failed (check {@link #lastError}).
     */
    /** Shared clients: the translation lanes issue many requests and a fresh
     * OkHttpClient per call defeats connection pooling / keep-alive. */
    private static final java.util.concurrent.ConcurrentHashMap<String, OkHttpClient> CLIENTS =
            new java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>();

    private static OkHttpClient sharedClient(boolean longRead) {
        final String key = longRead ? "long" : "short";
        OkHttpClient c = CLIENTS.get(key);
        if (c == null) {
            c = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(longRead ? 120 : 30, TimeUnit.SECONDS)
                    .build();
            CLIENTS.put(key, c);
        }
        return c;
    }

    public static java.util.List<String> listModels(String protocol, String baseUrl, String apiKey) {
        lastError = "";
        OkHttpClient client = sharedClient(false);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            Request.Builder builder = new Request.Builder().url(base + "/models");
            if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            } else if (PROTOCOL_GOOGLE.equals(protocol)) {
                builder.header("x-goog-api-key", apiKey);
            } else {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            Response response = client.newCall(builder.build()).execute();
            try {
                String text = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    lastError = classify(response.code()) + " " + response.code();
                    return null;
                }
                LinkedJSONObject json = new LinkedJSONObject(text);
                List<String> ids = new java.util.ArrayList<String>();
                JSONArray arr = json.optJSONArray("models");
                if (arr == null) {
                    arr = json.optJSONArray("data");
                }
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String id = arr.getJSONObject(i).optString("id",
                                arr.getJSONObject(i).optString("name"));
                        if (TxtUtils.isNotEmpty(id)) {
                            ids.add(id);
                        }
                    }
                }
                java.util.Collections.sort(ids, String.CASE_INSENSITIVE_ORDER);
                return ids;
            } finally {
                response.close();
            }
        } catch (java.net.SocketTimeoutException e) {
            LOG.e(e);
            lastError = "timeout";
        } catch (java.net.UnknownHostException e) {
            LOG.e(e);
            lastError = "network";
        } catch (Exception e) {
            LOG.e(e);
            lastError = "other";
        }
        return null;
    }

    /**
     * Single-shot (non-streaming) chat completion with explicit config; the
     * future AI features (translate / summarize) go through this method.
     *
     * @param thinking model reasoning mode; the wire field depends on the
     *                 protocol (Qwen3 chat_template_kwargs / Claude thinking
     *                 block / Gemini thinkingBudget)
     */
    public static TestResult chat(Context c, String protocol, String baseUrl, String apiKey,
            String model, String userText, int maxTokens, boolean thinking) {
        lastError = "";
        TestResult res = new TestResult();
        OkHttpClient client = sharedClient(true);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            Request request;
            if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                LinkedJSONObject body = new LinkedJSONObject();
                body.put("model", model);
                body.put("max_tokens", maxTokens);
                if (thinking) {
                    // extended thinking: budget must stay below max_tokens
                    int budget = Math.min(2048, maxTokens - 1024);
                    if (budget < 1024) {
                        budget = 1024;
                        maxTokens = 3072;
                    }
                    body.put("max_tokens", maxTokens);
                    body.put("thinking", new LinkedJSONObject()
                            .put("type", "enabled").put("budget_tokens", budget));
                }
                JSONArray messages = new JSONArray();
                messages.put(new LinkedJSONObject().put("role", "user").put("content", userText));
                body.put("messages", messages);
                request = new Request.Builder()
                        .url(base + "/messages")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                        .build();
            } else if (PROTOCOL_GOOGLE.equals(protocol)) {
                LinkedJSONObject body = new LinkedJSONObject();
                JSONArray contents = new JSONArray();
                JSONArray parts = new JSONArray();
                parts.put(new LinkedJSONObject().put("text", userText));
                contents.put(new LinkedJSONObject().put("parts", parts));
                body.put("contents", contents);
                LinkedJSONObject gen = new LinkedJSONObject();
                gen.put("maxOutputTokens", maxTokens);
                // thinkingBudget 0 = off, -1 = dynamic
                gen.put("thinkingConfig", new LinkedJSONObject()
                        .put("thinkingBudget", thinking ? -1 : 0));
                body.put("generationConfig", gen);
                request = new Request.Builder()
                        .url(base + "/models/" + model + ":generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                        .build();
            } else {
                // OpenAI-compatible: OpenAI / DeepSeek / gateways / Qwen3 on
                // llama.cpp & vLLM. The thinking flags are llama.cpp/vLLM
                // extensions — the official OpenAI API rejects unknown
                // top-level arguments with HTTP 400, so they are only sent
                // to non-OpenAI endpoints.
                LinkedJSONObject body = new LinkedJSONObject();
                body.put("model", model);
                body.put("max_tokens", maxTokens);
                body.put("stream", false);
                JSONArray messages = new JSONArray();
                messages.put(new LinkedJSONObject().put("role", "user").put("content", userText));
                body.put("messages", messages);
                if (!base.contains("api.openai.com")) {
                    body.put("chat_template_kwargs",
                            new LinkedJSONObject().put("enable_thinking", thinking));
                    body.put("enable_thinking", thinking);
                }
                request = new Request.Builder()
                        .url(base + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                        .build();
            }

            Response response = client.newCall(request).execute();
            try {
                String text = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    res.error = classify(response.code());
                    res.detail = "HTTP " + response.code() + " " + text.substring(0, Math.min(200, text.length()));
                    lastError = res.error + " " + response.code();
                    LOG.d("AiClient http", String.valueOf(response.code()),
                            text.substring(0, Math.min(300, text.length())));
                    return res;
                }
                res.reply = extractText(protocol, text);
                if (TxtUtils.isEmpty(res.reply)) {
                    // 2xx but no usable text: reasoning models may spend the
                    // whole budget on reasoning_content, or the shape differs
                    res.error = "empty";
                    res.detail = "HTTP 200 " + text.substring(0, Math.min(200, text.length()));
                    return res;
                }
                res.ok = true;
                res.truncated = isLengthTruncated(protocol, text);
                return res;
            } finally {
                response.close();
            }
        } catch (java.net.SocketTimeoutException e) {
            LOG.e(e);
            res.error = "timeout";
            return res;
        } catch (java.net.UnknownHostException e) {
            LOG.e(e);
            res.error = "network";
            return res;
        } catch (java.io.IOException e) {
            LOG.e(e);
            res.error = "network";
            return res;
        } catch (Exception e) {
            LOG.e(e);
            res.error = "other";
            return res;
        }
    }

    /** Pull the first text out of the protocol-specific response shape. */
    private static String extractText(String protocol, String body) {
        try {
            LinkedJSONObject json = new LinkedJSONObject(body);
            if (PROTOCOL_GOOGLE.equals(protocol)) {
                return json.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).optString("text");
            }
            if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                // extended thinking puts a {"type":"thinking"} block FIRST:
                // concatenate the actual text blocks instead of reading [0]
                final JSONArray content = json.getJSONArray("content");
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    final LinkedJSONObject block = content.getJSONObject(i);
                    if ("text".equals(block.optString("type"))) {
                        sb.append(block.optString("text"));
                    }
                }
                return sb.toString();
            }
            LinkedJSONObject message = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message");
            String content = message.optString("content");
            if (TxtUtils.isEmpty(content)) {
                // reasoning models: visible text may sit in reasoning_content
                content = message.optString("reasoning_content");
            }
            return content;
        } catch (Exception e) {
            // a 2xx with an unexpected shape still proves connectivity
            return "";
        }
    }

    /** True when the model stopped because it hit the output length cap. */
    private static boolean isLengthTruncated(String protocol, String body) {
        try {
            LinkedJSONObject json = new LinkedJSONObject(body);
            if (PROTOCOL_GOOGLE.equals(protocol)) {
                return "MAX_TOKENS".equals(json.getJSONArray("candidates")
                        .getJSONObject(0).optString("finishReason"));
            }
            if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                return "max_tokens".equals(json.optString("stop_reason"));
            }
            return "length".equals(json.getJSONArray("choices")
                    .getJSONObject(0).optString("finish_reason"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String classify(int code) {
        if (code == 401 || code == 403) {
            return "auth";
        }
        if (code == 429) {
            return "rate";
        }
        if (code == 404) {
            return "model";
        }
        return "other";
    }
}
