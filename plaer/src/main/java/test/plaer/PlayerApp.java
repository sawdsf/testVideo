package test.plaer;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

public class PlayerApp {

    private Pipeline pipeline;
    private Element videoBalance;
    private GstVideoComponent videoComponent;
    private JFrame frame;
    private JComboBox<String> filterBox;

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
        filterBox.addActionListener(e -> applyCurrentFilter());

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
            }

            pipeline = new Pipeline("playerPipeline");

            Element source = ElementFactory.make("filesrc", "source");
            source.set("location", filename);
            Element decode = ElementFactory.make("decodebin", "decode");
            Element convert = ElementFactory.make("videoconvert", "convert");
            videoBalance = ElementFactory.make("videobalance", "videoBalance");

            Element videoSink = videoComponent.getElement();

            pipeline.addMany(source, decode, convert, videoBalance, videoSink);
            source.link(decode);
            convert.link(videoBalance);
            videoBalance.link(videoSink);

            Element audioConvert = ElementFactory.make("audioconvert", "audioConvert");
            Element audioResample = ElementFactory.make("audioresample", "audioResample");
            Element audioSink = ElementFactory.make("autoaudiosink", "audioSink");
            pipeline.addMany(audioConvert, audioResample, audioSink);
            audioConvert.link(audioResample);
            audioResample.link(audioSink);

            decode.connect((Element.PAD_ADDED) (element, pad) -> {
                Caps caps = pad.getCurrentCaps();
                if (caps != null) {
                    String capsStr = caps.toString();
                    if (capsStr.startsWith("video/")) {
                        pad.link(convert.getStaticPad("sink"));
                    } else if (capsStr.startsWith("audio/")) {
                        pad.link(audioConvert.getStaticPad("sink"));
                    }
                }
            });

            pipeline.getBus().connect((Bus.ERROR) (src, code, msg) -> System.err.println("GStreamer ERROR: " + msg));
            pipeline.getBus().connect((Bus.EOS) src -> System.out.println("GStreamer: End of stream"));

            pipeline.play();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void applyCurrentFilter() {
        if (videoBalance == null) return;

        String selected = (String) filterBox.getSelectedItem();

        switch (selected) {
            case "Без фильтра":
                videoBalance.set("saturation", 1.0);
                videoBalance.set("contrast", 1.0);
                videoBalance.set("brightness", 0.0);
                break;
            case "Черно-белый":
                videoBalance.set("saturation", 0.0);
                videoBalance.set("contrast", 1.0);
                videoBalance.set("brightness", 0.0);
                break;
            case "Инверсия":
                videoBalance.set("saturation", 1.0);
                videoBalance.set("contrast", -1.0);
                videoBalance.set("brightness", 1.0);
                break;
            case "Размытие":
                videoBalance.set("saturation", 1.0);
                videoBalance.set("contrast", 0.8);
                videoBalance.set("brightness", 0.0);
                break;
        }
    }

    public static void main(String[] args) {
        new PlayerApp();
    }
}
