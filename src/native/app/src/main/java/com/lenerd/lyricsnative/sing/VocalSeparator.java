package com.lenerd.lyricsnative.sing;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Map;

public final class VocalSeparator implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final SpectralTransform transform = new SpectralTransform();
    private final float compensation;

    public VocalSeparator(byte[] model) throws OrtException {
        this(model, 1.03f);
    }

    public VocalSeparator(byte[] model,float compensation) throws OrtException {
        this.compensation=compensation;
        
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            options.addConfigEntry("session.inter_op.allow_spinning", "0");
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            
            session = environment.createSession(model, options);
        }
        
        var shape = ((TensorInfo)session.getInputInfo().get("input").getInfo()).getShape();
        
        if (shape.length != 4 || shape[1] != 4 || shape[2] != SpectralTransform.BINS) {
            session.close();

            throw new IllegalArgumentException("Wrong MDX model shape");
        }
    }
    
    public float[][] separate(float[][] pcm) throws OrtException {
        float[] features = transform.forward(pcm);
        
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), new long[]{1,4,SpectralTransform.BINS,SpectralTransform.TIMES});
             var result = session.run(Map.of("input", input))) {
                
            FloatBuffer buffer = ((OnnxTensor)result.get(0)).getFloatBuffer();
            float[] prediction = new float[features.length];
            
            buffer.get(prediction);

            for (float value : prediction) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite model output");
            }

            float[][] vocals = transform.inverse(prediction);
            for (float[] channel : vocals) {
                for (int i=0;i<channel.length;i++) {
                    channel[i] *= compensation;
                }
            }

            return vocals;
        }
    }

    @Override 
    public void close() throws OrtException { 
        session.close(); 
    }
}
