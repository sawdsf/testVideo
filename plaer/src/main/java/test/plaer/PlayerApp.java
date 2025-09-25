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
import org.freedesktop.gstreamer.swing.GstVideoComponent;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.State;
import org.freedesktop.gstreamer.elements.DecodeBin;

public class PlayerApp {

    private Pipeline pipeline;
    private Element videoBalance;
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
    
    private JComboBox<String> filterCombo;

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

        playBtn.addActionListener(e -> { if (pipeline != null) pipeline.play(); });
        pauseBtn.addActionListener(e -> { if (pipeline != null) pipeline.pause(); });
        stopBtn.addActionListener(e -> { if (pipeline != null) pipeline.stop(); });
        filterBox.addActionListener(e -> {applyCurrentFilter((String) filterCombo.getSelectedItem());});

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

            pipeline.addMany(source, decode, audioConvert, audioResample, audioSink);
            pipeline.addMany(videoSink);

            source.link(decode);

            decode.connect((Element.PAD_ADDED) (element, pad) -> {
                try {
                    Caps caps = pad.getCurrentCaps();
                    String capsStr = (caps == null) ? "" : caps.toString();
                    if (capsStr.startsWith("audio/")) {
                        if (!pipeline.getElements().contains(audioConvert)) {
                            pipeline.addMany(audioConvert, audioResample, audioSink);
                            audioConvert.link(audioResample);
                            audioResample.link(audioSink);
                        }
                        pad.link(audioConvert.getStaticPad("sink"));
                    } else if (capsStr.startsWith("video/")) {
                        buildVideoChain((String) filterCombo.getSelectedItem());
                        pad.link(videoConvertIn.getStaticPad("sink"));
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });

            pipeline.getBus().connect((Bus.ERROR) (src, code, msg) -> System.err.println("GStreamer ERROR: " + msg));
            pipeline.getBus().connect((Bus.EOS) src -> System.out.println("GStreamer: End of stream"));

            pipeline.play();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private synchronized void buildVideoChain(String filterName) {
        boolean wasPlaying = false;
        if (pipeline != null) {
            State state = pipeline.getState(0);
            wasPlaying = (state == State.PLAYING);
            pipeline.pause();
        }

        try {
            if (videoConvertIn != null) {
                try { pipeline.remove(videoConvertIn); } catch (Exception ignored) {}
                videoConvertIn = null;
            }
            if (activeFilter != null) {
                try { pipeline.remove(activeFilter); } catch (Exception ignored) {}
                activeFilter = null;
            }
            if (videoConvertOut != null) {
                try { pipeline.remove(videoConvertOut); } catch (Exception ignored) {}
                videoConvertOut = null;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        
        videoConvertIn = ElementFactory.make("videoconvert", "videoConvertIn");
        videoConvertOut = ElementFactory.make("videoconvert", "videoConvertOut");
        activeFilter = createFilterElementByName(filterName);

        
        try {
            if (activeFilter != null) {
                pipeline.addMany(videoConvertIn, activeFilter, videoConvertOut, videoSink);
                videoConvertIn.link(activeFilter);
                activeFilter.link(videoConvertOut);
                videoConvertOut.link(videoSink);
            } else {
                pipeline.addMany(videoConvertIn, videoConvertOut, videoSink);
                videoConvertIn.link(videoConvertOut);
                videoConvertOut.link(videoSink);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            try {
                activeFilter = null;
                pipeline.addMany(videoConvertIn, videoConvertOut, videoSink);
                videoConvertIn.link(videoConvertOut);
                videoConvertOut.link(videoSink);
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }

        if (pipeline != null) {
            if (wasPlaying) pipeline.play(); else pipeline.pause();
        }
    }

    private Element createFilterElementByName(String name) {
        try {
            if ("Черно-белый".equals(name)) {
                Element balance = ElementFactory.make("videobalance", "vbalance");
                balance.set("saturation", 0.0f);
                return balance;
            } else if ("Инверсия".equals(name)) {
                Element freiInvert = ElementFactory.make("frei0r-filter-invert0r", "freiInvert");
                if (freiInvert != null) return freiInvert;
                Element videoInvert = ElementFactory.make("videoinvert", "videoInvert");
                if (videoInvert != null) return videoInvert;
                return null;
            } else if ("Размытие".equals(name)) {
                Element freiBlur = ElementFactory.make("frei0r-filter-iir-blur", "freiBlur");
                if (freiBlur != null) return freiBlur;
                return null;
            } else {
                return null;
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