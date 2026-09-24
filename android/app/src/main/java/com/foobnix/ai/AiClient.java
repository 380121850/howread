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
        return ask(c, userText, null);
    }

    /** Incremental output of a live-streamed completion: onDelta receives the
     *  full text so far (throttled to ~150 ms) on the calling thread. */
    public interface StreamCallback {
        void onDelta(String fullTextSoFar);
    }

    /** Same as {@link #ask(Context, String)} with live streaming (OpenAI
     *  compatible endpoints; other protocols answer in one block). */
    public static TestResult ask(Context c, String userText, StreamCallback stream) {
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
                AppState.get().aiThinking, stream);
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

    public static java.util.List<String> listModels(String protocol, String baseUrl, String apiKey,
            StringBuilder errOut) {
        lastError = "";
        if (errOut != null) {
            errOut.setLength(0);
        }
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
                    if (errOut != null) {
                        errOut.append(lastError);
                    }
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
                        // Gemini lists entries as "models/<id>"; chat() builds
                        // "/models/<id>" itself — storing the prefix made
                        // every model picked from the list 404
                        if (id.startsWith("models/")) {
                            id = id.substring("models/".length());
                        }
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
        if (errOut != null && errOut.length() == 0) {
            errOut.append(lastError);
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
        return chat(c, protocol, baseUrl, apiKey, model, userText, maxTokens, thinking, null);
    }

    /** Chat with an optional live-stream callback (OpenAI-compatible only). */
    public static TestResult chat(Context c, String protocol, String baseUrl, String apiKey,
            String model, String userText, int maxTokens, boolean thinking, StreamCallback stream) {
        final long t0 = System.currentTimeMillis();
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
                // OpenAI-compatible: OpenAI / DeepSeek / GLM / gateways /
                // Qwen3 on llama.cpp & vLLM. The thinking flags are server
                // extensions — the official OpenAI API rejects unknown
                // top-level arguments with HTTP 400, so they are only sent
                // to non-OpenAI endpoints (a 400 falls back to a plain
                // request, see chatOpenAi).
                LinkedJSONObject body = new LinkedJSONObject();
                body.put("model", model);
                body.put("max_tokens", maxTokens);
                JSONArray messages = new JSONArray();
                messages.put(new LinkedJSONObject().put("role", "user").put("content", userText));
                body.put("messages", messages);
                final boolean officialOpenAi = base.contains("api.openai.com");
                // GLM/zhipu-style explicit switch: GLM-4.5+ THINKS BY
                // DEFAULT and silently ignores the vLLM-style flags above
                // (measured 4-5x slower answers when left thinking). Models
                // that reject the field with HTTP 400 are remembered and
                // skip the extension fields from then on.
                final boolean extensionsBlocked = officialOpenAi
                        || thinkingUnsupported(base, model);
                if (!extensionsBlocked) {
                    body.put("chat_template_kwargs",
                            new LinkedJSONObject().put("enable_thinking", thinking));
                    body.put("enable_thinking", thinking);
                    body.put("thinking", new LinkedJSONObject()
                            .put("type", thinking ? "enabled" : "disabled"));
                }
                body.put("stream", stream != null);
                return chatOpenAi(client, base, apiKey, body, officialOpenAi, stream, res, t0);
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
                android.util.Log.i("AITRANS", "chat total=" + (System.currentTimeMillis() - t0)
                        + "ms out=" + res.reply.length() + " protocol=" + protocol);
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

    /** base|model keys whose endpoint rejected the GLM "thinking" switch with
     *  HTTP 400 (e.g. glm-5.x) — remembered so later requests skip the field
     *  entirely instead of paying a 400 round-trip every time. */
    private static final java.util.Set<String> THINKING_UNSUPPORTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static boolean thinkingUnsupported(String base, String model) {
        return THINKING_UNSUPPORTED.contains(base + "|" + model);
    }

    private static Request openAiRequest(String base, String apiKey, LinkedJSONObject body) {
        return new Request.Builder()
                .url(base + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .build();
    }

    /**
     * OpenAI-compatible execution with live streaming: stream=true when a
     * callback is given, SSE "data:" lines feed the callback (throttled) and
     * accumulate into the same TestResult shape as the plain call. A strict
     * gateway answering 400 to the extension fields (thinking / stream) gets
     * exactly one plain retry without them, and a 2xx answer that never
     * streams falls back to one plain request — nothing fails that used to
     * work.
     */
    private static TestResult chatOpenAi(OkHttpClient client, String base, String apiKey,
            LinkedJSONObject body, boolean officialOpenAi, StreamCallback stream, TestResult res,
            long t0) throws java.io.IOException {
        Response response = client.newCall(openAiRequest(base, apiKey, body)).execute();
        if (response.code() == 400 && !officialOpenAi) {
            String err = response.body() == null ? "" : response.body().string();
            response.close();
            android.util.Log.i("AITRANS", "chat HTTP 400 -> retry without extension fields "
                    + err.substring(0, Math.min(120, err.length())));
            if (err.contains("think")) {
                // this model rejects the GLM thinking switch: skip the field
                // on future requests instead of paying the 400 round-trip
                THINKING_UNSUPPORTED.add(base + "|" + body.optString("model"));
            }
            res.error = "";
            res.detail = "";
            body.remove("thinking");
            body.remove("chat_template_kwargs");
            body.remove("enable_thinking");
            body.put("stream", false);
            response = client.newCall(openAiRequest(base, apiKey, body)).execute();
        }
        try {
            if (body.optBoolean("stream", false) && response.isSuccessful()) {
                TestResult sres = readStreamOpenAi(response, stream, res);
                if (sres.ok) {
                    return sres;
                }
                if ("empty".equals(sres.error)) {
                    // 2xx but no SSE events (a gateway that ignored
                    // stream=true): one plain retry
                    android.util.Log.i("AITRANS", "stream empty -> plain retry");
                    response.close();
                    res.error = "";
                    res.detail = "";
                    body.put("stream", false);
                    response = client.newCall(openAiRequest(base, apiKey, body)).execute();
                } else {
                    return sres;
                }
            }
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                res.error = classify(response.code());
                res.detail = "HTTP " + response.code() + " " + text.substring(0, Math.min(200, text.length()));
                lastError = res.error + " " + response.code();
                LOG.d("AiClient http", String.valueOf(response.code()),
                        text.substring(0, Math.min(300, text.length())));
                return res;
            }
            res.reply = extractText(PROTOCOL_OPENAI, text);
            if (TxtUtils.isEmpty(res.reply)) {
                res.error = "empty";
                res.detail = "HTTP 200 " + text.substring(0, Math.min(200, text.length()));
                return res;
            }
            res.ok = true;
            res.truncated = isLengthTruncated(PROTOCOL_OPENAI, text);
            android.util.Log.i("AITRANS", "chat total=" + (System.currentTimeMillis() - t0)
                    + "ms out=" + res.reply.length() + " stream=false");
            return res;
        } finally {
            response.close();
        }
    }

    /** Consume an OpenAI-compatible SSE response: throttled onDelta with the
     *  growing text; content deltas accumulate, reasoning deltas are kept
     *  only as the empty-content fallback (mirrors the plain parsing). */
    private static TestResult readStreamOpenAi(Response response, StreamCallback stream,
            TestResult res) throws java.io.IOException {
        final long t0 = System.currentTimeMillis();
        long tFirst = 0;
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        String finish = null;
        long lastCb = 0;
        java.io.BufferedReader in = null;
        try {
            in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    response.body().byteStream(), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    res.error = "cancelled";
                    android.util.Log.i("AITRANS", "stream cancelled, got "
                            + content.length() + " chars");
                    return res;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                LinkedJSONObject obj;
                try {
                    obj = new LinkedJSONObject(data);
                } catch (Exception e) {
                    continue;
                }
                JSONArray choices = obj.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    continue;
                }
                LinkedJSONObject ch0 = choices.getJSONObject(0);
                LinkedJSONObject delta = ch0.optJSONObject("delta");
                if (delta != null) {
                    String piece = delta.optString("content");
                    if (TxtUtils.isNotEmpty(piece)) {
                        content.append(piece);
                    }
                    String think = delta.optString("reasoning_content");
                    if (TxtUtils.isNotEmpty(think)) {
                        reasoning.append(think);
                    }
                    if (tFirst == 0 && (content.length() > 0 || reasoning.length() > 0)) {
                        tFirst = System.currentTimeMillis() - t0;
                    }
                }
                String fr = ch0.optString("finish_reason");
                if (TxtUtils.isNotEmpty(fr)) {
                    finish = fr;
                }
                if (stream != null) {
                    final long now = System.currentTimeMillis();
                    if (now - lastCb >= 150) {
                        lastCb = now;
                        stream.onDelta(content.toString());
                    }
                }
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (stream != null) {
            stream.onDelta(content.toString()); // final flush
        }
        String reply = content.toString();
        if (TxtUtils.isEmpty(reply)) {
            reply = reasoning.toString();
        }
        android.util.Log.i("AITRANS", "stream ttft=" + tFirst + "ms total="
                + (System.currentTimeMillis() - t0) + "ms out=" + reply.length()
                + " finish=" + finish);
        if (TxtUtils.isEmpty(reply)) {
            res.error = "empty";
            return res;
        }
        res.reply = reply;
        res.ok = true;
        res.truncated = "length".equals(finish);
        return res;
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
