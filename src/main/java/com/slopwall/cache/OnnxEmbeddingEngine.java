package com.slopwall.cache;

import ai.onnxruntime.*;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;

public final class OnnxEmbeddingEngine implements EmbeddingEngine {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final int dims;

    public OnnxEmbeddingEngine(Path modelPath) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        var opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath.toString(), opts);
        dims = inferDims();
    }

    @Override
    public float[] embed(String text) {
        // Placeholder tokenization. Replace with proper tokenizer for production.
        var tokens = simpleTokenize(text);
        var inputIds = LongBuffer.wrap(tokens);
        var attentionMask = LongBuffer.allocate(tokens.length);
        for (int i = 0; i < tokens.length; i++) attentionMask.put(1L);
        attentionMask.flip();

        long[] shape = {1, tokens.length};
        try (var inputIdsTensor = OnnxTensor.createTensor(env, inputIds, shape);
             var maskTensor = OnnxTensor.createTensor(env, attentionMask, shape)) {
            var inputs = Map.of("input_ids", inputIdsTensor, "attention_mask", maskTensor);
            try (var output = session.run(inputs)) {
                // last_hidden_state shape: [1, seq_len, dims]
                var hidden = (float[][][]) output.get(0).getValue();
                // CLS token pool: hidden[0][0]
                float[] cls = hidden[0][0];
                return l2normalize(cls);
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        }
    }

    @Override
    public int dimensions() { return dims; }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) {}
    }

    private int inferDims() throws OrtException {
        // Probe dims from output metadata; fall back to 384 (all-MiniLM-L6-v2 default)
        try {
            var outputInfo = session.getOutputInfo();
            if (!outputInfo.isEmpty()) {
                var info = outputInfo.values().iterator().next().getInfo();
                if (info instanceof TensorInfo ti) {
                    long[] shape = ti.getShape();
                    if (shape.length >= 3 && shape[2] > 0) return (int) shape[2];
                }
            }
        } catch (Exception ignored) {}
        return 384;
    }

    private long[] simpleTokenize(String text) {
        // Placeholder: real deployment must use proper tokenizer matching the model
        var words = text.trim().split("\\s+");
        var ids = new long[Math.min(words.length + 2, 512)]; // +2 for [CLS],[SEP]
        ids[0] = 101L; // [CLS]
        for (int i = 1; i < ids.length - 1; i++) ids[i] = Math.abs(words[i - 1].hashCode()) % 30000L;
        ids[ids.length - 1] = 102L; // [SEP]
        return ids;
    }

    private float[] l2normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }
}
