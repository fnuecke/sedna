package li.cil.sedna.device.virtio.gpu;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.virtio.VirtIOKeyboardDevice;
import li.cil.sedna.memory.MemoryMaps;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.MemoryStack;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

class TestWindow extends JFrame {
    ByteBuffer data;
    public TestWindow() {
        setVisible(true);
        setLocationRelativeTo(null);
        setSize(800, 600);
        setContentPane(new DrawPane());
    }

    public void setData(ByteBuffer data) {
        this.data = data;
        repaint();
    }

    class DrawPane extends JPanel {
        @Override
        public void paint(Graphics graphics) {
            BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);
            IntBuffer intBuffer = IntBuffer.allocate(800 * 600 * 3);
            while(data.hasRemaining()) {
                intBuffer.put((data.get() << 16 | data.get() << 8 | data.get()));
                data.get();
            }
            image.setRGB(0, 0, 800, 600, intBuffer.array(), 0, 800);
            graphics.drawImage(image, 0, 0, 800, 600, null);
        }
    }
}

public class GPUViewer {
    private long window;
    private final MemoryMap memoryMap;

    private int texture;
    private TestWindow testWindow = new TestWindow();

    public GPUViewer(MemoryMap memoryMap, VirtIOKeyboardDevice keyboard) {
        this.memoryMap = memoryMap;
        init();

        /*
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        glfwTerminate();
        Objects.requireNonNull(glfwSetErrorCallback(null)).free();
         */
    }

    private void init() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if ( !glfwInit() )
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 1);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

        // Create the window
        window = glfwCreateWindow(800, 600, "Hello World!", NULL, NULL);
        if ( window == NULL )
            throw new RuntimeException("Failed to create the GLFW window");

        // Setup a key callback. It will be called every time a key is pressed, repeated or released.
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            System.out.println(key);
        });

        // Get the thread stack and push a new frame
        try ( MemoryStack stack = stackPush() ) {
            IntBuffer pWidth = stack.mallocInt(1); // int*
            IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);

            // Get the resolution of the primary monitor
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        } // the stack frame is popped automatically

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);

        // Make the window visible
        glfwShowWindow(window);

        GL.createCapabilities();
        GLUtil.setupDebugMessageCallback();
        glClearColor(255f, 255f, 255f, 1f);

        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        //glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        //glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glEnable(GL_BLEND);
        glBlendFunc (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void view(final long address, final int length) {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glPushMatrix();
        glEnable(GL_TEXTURE_2D);
        glTranslatef(-400, -300, -1);

        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            MemoryMaps.load(memoryMap, address, buffer);
        } catch (MemoryAccessException e) {
            e.printStackTrace();
        }
        buffer.flip();
        //testWindow.setData(buffer);

        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 800, 600, 0, GL_RGBA, GL_FLOAT, buffer);

        glBindTexture(GL_TEXTURE_2D, texture);
        glBegin(GL_QUADS);
        glTexCoord2f(0, 0);
        glColor3f(255, 0, 0); glVertex2f(0, 0);
        glTexCoord2f(0, 600);
        glColor3f(255, 0, 0); glVertex2f(0, 600);
        glTexCoord2f(800, 600);
        glColor3f(255, 0, 0); glVertex2f(800, 600);
        glTexCoord2f(800, 0);
        glColor3f(255, 0, 0); glVertex2f(800, 0);
        glEnd();

        glDisable(GL_TEXTURE_2D);
        glPopMatrix();

        glfwSwapBuffers(window);
        glfwPollEvents();
    }
}
