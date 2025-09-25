package test.plaer;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.State;
import org.freedesktop.gstreamer.elements.DecodeBin;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

public class PlayerApp {

    private Pipeline pipeline;
    private GstVideoComponent videoComponent;
    private JFrame frame;
    private JComboBox<String> filterBox;
    private Element videoConvertIn;
    private Element activeFilter;
    private Element videoConvertOut;
    private Element videoSink;
    private Element audioConvert;
    private Element audioResample;
    private Element audioSink;
    private State pipelineState = State.NULL;

    public PlayerApp() {
        Gst.init("PlayerApp", new String[]{});
        videoComponent = new GstVideoComponent();
        createUI();
    }

    private void createUI() {
        frame = new JFrame("Простой плеер");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        JButton openBtn = new JButton("Открыть");
        JButton playBtn = new JButton("Старт/продолжить");
        JButton pauseBtn = new JButton("Пауза");
        JButton stopBtn = new JButton("Стоп");
        String[] filters = {"Без фильтра", "Черно-белый", "Инверсия", "Размытие"};
        filterBox = new JComboBox<>(filters);
        JPanel controls = new JPanel();
        controls.add(openBtn);
        controls.add(playBtn);
        controls.add(pauseBtn);
        controls.add(stopBtn);
        controls.add(filterBox);
        frame.add(videoComponent, BorderLayout.CENTER);
        frame.add(controls, BorderLayout.SOUTH);
        openBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                openFile(fc.getSelectedFile().getAbsolutePath());
            }
        });
        playBtn.addActionListener(e -> { if (pipeline != null) {pipeline.play();pipelineState = State.PLAYING;} });
        pauseBtn.addActionListener(e -> { if (pipeline != null) {pipeline.pause();pipelineState = State.PAUSED;} });
        stopBtn.addActionListener(e -> { if (pipeline != null) {pipeline.stop();pipelineState = State.NULL;} });
        filterBox.addActionListener(e -> applyCurrentFilter((String) filterBox.getSelectedItem()));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (pipeline != null) pipeline.stop();
                Gst.deinit();
            }
        });
        frame.setVisible(true);
    }

    private void openFile(String filename) {
        try {
            if (pipeline != null) {
                pipeline.stop();
                pipeline.dispose();
                pipeline = null;
            }
            pipeline = new Pipeline("playerPipeline");
            Element source = ElementFactory.make("filesrc", "source");
            source.set("location", filename);
            DecodeBin decode = new DecodeBin("decoder");
            audioConvert = ElementFactory.make("audioconvert", "audioConvert");
            audioResample = ElementFactory.make("audioresample", "audioResample");
            audioSink = ElementFactory.make("autoaudiosink", "audioSink");
            videoSink = videoComponent.getElement();
            pipeline.addMany(source, decode, audioConvert, audioResample, audioSink, videoConvertIn = ElementFactory.make("videoconvert", "videoConvertIn"), videoConvertOut = ElementFactory.make("videoconvert", "videoConvertOut"), videoSink);
            source.link(decode);
            decode.connect((Element.PAD_ADDED) (element, pad) -> {
                try {
                    Caps caps = pad.getCurrentCaps();
                    String capsStr = (caps == null) ? "" : caps.toString();
                    if (capsStr.startsWith("audio/")) {
                        pad.link(audioConvert.getStaticPad("sink"));
                        audioConvert.link(audioResample);
                        audioResample.link(audioSink);
                    } else if (capsStr.startsWith("video/")) {
                        pad.link(videoConvertIn.getStaticPad("sink"));
                        if (activeFilter == null) videoConvertIn.link(videoConvertOut);
                        videoConvertOut.link(videoSink);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
            pipeline.getBus().connect((Bus.ERROR) (src, code, msg) -> System.err.println("GStreamer ERROR: " + msg));
            pipeline.getBus().connect((Bus.EOS) src -> System.out.println("GStreamer: End of stream"));
            pipeline.play();
            pipelineState = State.PLAYING;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private synchronized void buildVideoChain(String filterName) {
        boolean wasPlaying = (pipelineState == State.PLAYING);
        if (pipeline != null) pipeline.pause();
        try {
            if (activeFilter != null) {
                try { pipeline.remove(activeFilter); } catch (Exception ignored) {}
                activeFilter = null;
            }
        } catch (Exception ex) { ex.printStackTrace(); }
        if ("Без фильтра".equals(filterName)) {
            videoConvertIn.unlink(videoConvertOut);
            videoConvertIn.link(videoConvertOut);
            videoConvertOut.link(videoSink);
        } else {
            activeFilter = createFilterElementByName(filterName);
            if (activeFilter != null) {
                pipeline.addMany(activeFilter);
                videoConvertIn.unlink(videoConvertOut);
                videoConvertIn.link(activeFilter);
                activeFilter.link(videoConvertOut);
                videoConvertOut.link(videoSink);
            }
        }
        if (pipeline != null) {
            if (wasPlaying) { pipeline.play(); pipelineState = State.PLAYING; } else { pipeline.pause(); pipelineState = State.PAUSED; }
        }
    }

    private Element createFilterElementByName(String name) {
    try {
        if ("Черно-белый".equals(name)) {
            Element balance = ElementFactory.make("videobalance", "vbalance");
            balance.set("saturation", 0.0f);
            balance.set("brightness", 0.0f);
            balance.set("contrast", 1.0f);
            return balance;
        } else if ("Инверсия".equals(name)) {
            Element balance = ElementFactory.make("videobalance", "vbalanceInvert");
            balance.set("saturation", 1.0f);    // цвет сохраняем
            balance.set("brightness", 1.0f);    // смещение вверх
            balance.set("contrast", -1.0f);     // инверсия контраста
            return balance;
        } else if ("Размытие".equals(name)) {
            Element blur = ElementFactory.make("gaussianblur", "gaussianBlur");
            return blur;
        } else {
            return null; // Без фильтра
        }
    } catch (Exception ex) {
        ex.printStackTrace();
        return null;
    }
}

    private void applyCurrentFilter(String selectedFilter) {
        if (pipeline == null) return;
        buildVideoChain(selectedFilter);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PlayerApp());
    }
}
