package com.javier.reservas;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Set;

/**
 * Ejecuta los modelos del detector y del lector de placas con ONNX Runtime (Microsoft, licencia MIT).
 * Corre en el procesador del equipo, sin internet.
 */
public final class EjecutorOrt implements MotorPlacas.Ejecutor {

    private final OrtEnvironment entorno;
    private final OrtSession detector;
    private final OrtSession lector;
    private final String entradaDetector;
    private final String entradaLector;
    private final String salidaLector;

    /** @param modeloDetector bytes del .onnx del detector; @param modeloLector bytes del .onnx del lector */
    public EjecutorOrt(byte[] modeloDetector, byte[] modeloLector) throws OrtException {
        entorno = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opcDet = new OrtSession.SessionOptions();
        opcDet.setIntraOpNumThreads(3);
        opcDet.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        OrtSession.SessionOptions opcLec = new OrtSession.SessionOptions();
        opcLec.setIntraOpNumThreads(2);
        opcLec.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        detector = entorno.createSession(modeloDetector, opcDet);
        lector = entorno.createSession(modeloLector, opcLec);
        entradaDetector = detector.getInputNames().iterator().next();
        entradaLector = lector.getInputNames().iterator().next();
        // El lector entrega «plate» (los símbolos) y «region» (el país): solo se pide el primero
        Set<String> salidas = lector.getOutputNames();
        salidaLector = salidas.contains("plate") ? "plate" : salidas.iterator().next();
    }

    @Override
    public float[] detectar(float[] chw, int t) throws OrtException {
        OnnxTensor entrada = OnnxTensor.createTensor(entorno, FloatBuffer.wrap(chw), new long[]{1, 3, t, t});
        try (OrtSession.Result r = detector.run(Collections.singletonMap(entradaDetector, entrada))) {
            return aArreglo((OnnxTensor) r.get(0));
        } finally {
            entrada.close();
        }
    }

    @Override
    public float[] leer(byte[] rgb) throws OrtException {
        OnnxTensor entrada = OnnxTensor.createTensor(
                entorno, ByteBuffer.wrap(rgb), new long[]{1, MotorPlacas.OCR_ALTO, MotorPlacas.OCR_ANCHO, 3}, OnnxJavaType.UINT8);
        try (OrtSession.Result r = lector.run(
                Collections.singletonMap(entradaLector, entrada), Collections.singleton(salidaLector))) {
            return aArreglo((OnnxTensor) r.get(0));
        } finally {
            entrada.close();
        }
    }

    private static float[] aArreglo(OnnxTensor t) {
        FloatBuffer fb = t.getFloatBuffer();
        float[] a = new float[fb.remaining()];
        fb.get(a);
        return a;
    }

    @Override
    public void close() {
        try {
            detector.close();
        } catch (Exception ignorada) {
        }
        try {
            lector.close();
        } catch (Exception ignorada) {
        }
    }
}
