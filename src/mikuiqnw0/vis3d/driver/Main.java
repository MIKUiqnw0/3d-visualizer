package mikuiqnw0.vis3d.driver;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import edu.emory.mathcs.jtransforms.fft.FloatFFT_1D;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.kc7bfi.jflac.FLACDecoder;
import org.kc7bfi.jflac.PCMProcessor;
import org.kc7bfi.jflac.metadata.StreamInfo;
import org.kc7bfi.jflac.util.ByteData;

/**
 * This program is developed as an experiment + learning resource into
 * digital signal processing concepts and object manipulation using audio data.
 *
 * @author MIKUiqnw0
 * @version 0.11
 * @since 9/03/14
 */
public class Main extends SimpleApplication implements PCMProcessor {

    private InputStream is;
    private AudioFormat fmt;
    private SourceDataLine line;
    private Vector listeners = new Vector();
    private Thread audioThread;
    protected static final int DIVISIBLE = 2048;
    private Geometry[] geom = new Geometry[DIVISIBLE];
    private float[] geomScale = new float[DIVISIBLE];

    public static void main(String[] args) {
        Main app = new Main();
        AppSettings settings = new AppSettings(true);
        settings.setWidth(1024);
        settings.setHeight(768);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.showSettings = false;

        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        drawGraph();
        flyCam.setMoveSpeed(100f);
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    audioInit();
                } catch (IOException ex) {
                    Logger.getLogger(Main.class.getName()).log(Level.INFO, null, ex);
                }
            }
        });

        audioThread.start();
    }

    private void drawGraph() {
        Box b = new Box(Vector3f.ZERO, .05f, 1f, .1f);
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        Material mat2 = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Blue);
        mat2.setColor("Color", ColorRGBA.Red);

        float spacing = 0;
        for (int i = 0; i < geom.length; ++i) {
            geom[i] = new Geometry("Box", b);
            geom[i].setLocalTranslation(spacing, 0, 0);
            if (i < geom.length / 2) {
                geom[i].setMaterial(mat);
            } else {
                geom[i].setMaterial(mat2);
            }
            rootNode.attachChild(geom[i]);
            spacing += .15f;
        }
    }

    private void audioInit() throws IOException {
        FLACDecoder decoder;
        FileDialog dialog = new FileDialog((Dialog) null, "Select a .flac file", FileDialog.LOAD);
        dialog.setFile("*.flac");
        dialog.setVisible(true);
        
        File flacFile = new File(dialog.getDirectory() + dialog.getFile());
        dialog.dispose();
        
        if(flacFile.canRead()) {
            is = new FileInputStream(flacFile);
        } else {
            is = getClass().getResourceAsStream("/sounds/track.flac");
        }

        if (is != null) {
            decoder = new FLACDecoder(is);
            decoder.addPCMProcessor(this);
            try {
                decoder.decode();
            } catch (EOFException ex) {
                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
            }
            is.close();
            line.drain();
            line.close();
            listeners.clear();
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        int geoElement = 0;
        for (Geometry geo : geom) {
            if(geomScale[geoElement] > geo.getLocalScale().y) {
                geo.setLocalScale(1, geomScale[geoElement], 1);
            } else {
                if(geo.getLocalScale().y > 0) {
                    geo.setLocalScale(1, geo.getLocalScale().y - 0.5f, 1);
                } else {
                    geo.setLocalScale(1, 0, 1);
                }
            }
            ++geoElement;
        }
    }

    @Override
    public void processStreamInfo(StreamInfo streamInfo) {
        DataLine.Info info;

        try {
            fmt = streamInfo.getAudioFormat();
            printMetadata();
            info = new DataLine.Info(SourceDataLine.class, fmt, AudioSystem.NOT_SPECIFIED);
            line = (SourceDataLine) AudioSystem.getLine(info);
            int size = listeners.size();
            for (int index = 0; index < size; index++) {
                line.addLineListener((LineListener) listeners.get(index));
            }
            line.open(fmt, AudioSystem.NOT_SPECIFIED);
            line.start();
        } catch (LineUnavailableException e) {
        }
    }

    @Override
    public void processPCM(ByteData pcm) {
        ByteBuffer buf = ByteBuffer.wrap(pcm.getData());

        // Channel Split
        short[] left = new short[pcm.getLen() / fmt.getFrameSize()];
        short[] right = new short[pcm.getLen() / fmt.getFrameSize()];

        float[] fftLeft = new float[left.length];
        float[] mag = new float[fftLeft.length / 2];

        // Split left and right channels from the byte data
        for (int i = 0; i < left.length; ++i) {
            left[i] = buf.getShort();
            right[i] = buf.getShort();
        }
        
        // Apply Hanning window to the left channel
        hanningWindow(left, fftLeft);
        
        // Find the min/max 
        float max = 0;
        float min = 0;
        for(int i = 0; i < fftLeft.length; ++i) {
            if(fftLeft[i] > max) {
                max = fftLeft[i];
            } else if(fftLeft[i] < min) {
                min = fftLeft[i];
            }
        }
        
        // Scale values between -1 - 1
        float signedInverse = 1.0f / (max - min);
        for(int i = 0; i < fftLeft.length; ++i) {
            fftLeft[i] = (float) ((((fftLeft[i] - min) * signedInverse) * 2.0) - 1);
        }

        // In-place real forward on left channel data
        FloatFFT_1D cfft = new FloatFFT_1D(left.length);
        cfft.realForward(fftLeft);
        
        // Power Spectral Density
        // Get magnitudes, convert to decibels and send to geomScale to be applied
        // in the simpleUpdate loop.
        for (int i = 0; i < mag.length; ++i) {
            mag[i] = (float) Math.sqrt((fftLeft[i*2] * fftLeft[i*2]) + (fftLeft[i*2+1] * fftLeft[i*2+1]));
            geomScale[i] = (float) (20f * Math.log10(mag[i]));
        }

        line.write(pcm.getData(), 0, pcm.getLen());
    }

    private void hanningWindow(short[] in, float[] store) {
        for(int i = 0; i < in.length; ++i) {
            float mult = (float) (0.5 * (1 - Math.cos((2*Math.PI*i)/in.length)));
            store[i] = in[i] * mult;
        }
    }

    public void addListener(LineListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LineListener listener) {
        listeners.removeElement(listener);
    }

    private void printMetadata() {
        System.out.println(fmt.toString());
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            if (is != null) {
                is.close();
            }
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        line.stop();
        line.flush();
        line.close();
    }
}
