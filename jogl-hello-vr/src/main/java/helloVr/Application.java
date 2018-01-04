/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package helloVr;

import static com.jogamp.opengl.GL.GL_DONT_CARE;
import static com.jogamp.opengl.GL.GL_FRAMEBUFFER;
import static com.jogamp.opengl.GL.GL_LINEAR;
import static com.jogamp.opengl.GL2ES2.GL_DEBUG_OUTPUT_SYNCHRONOUS;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.jogamp.newt.Display;
import com.jogamp.newt.NewtFactory;
import com.jogamp.newt.Screen;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.opengl.GLWindow;
import static com.jogamp.opengl.GL.GL_COLOR_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DRAW_FRAMEBUFFER;
import static com.jogamp.opengl.GL.GL_MULTISAMPLE;
import static com.jogamp.opengl.GL.GL_NO_ERROR;
import static com.jogamp.opengl.GL.GL_READ_FRAMEBUFFER;
import static com.jogamp.opengl.GL2ES3.GL_COLOR;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.Animator;
import com.jogamp.opengl.util.GLBuffers;

import glm.mat._4.Mat4;
import glm.vec._2.i.Vec2i;
import glutil.BufferUtils;
import glutil.GlDebugOutput;
import one.util.streamex.IntStreamEx;
import vr.IVRCompositor_FnTable;
import vr.IVRSystem;
import vr.Texture_t;
import vr.TrackedDevicePose_t;
import vr.VR;
import vr.VRControllerState_t;
import vr.VREvent_t;

/**
 *
 * @author GBarbieri
 */
public class Application implements GLEventListener, KeyListener {

    public static final String SHADERS_ROOT = "src/main/resources/shaders";

    private static GLWindow glWindow;
    private static Animator animator;

    public static void main(String[] args) {

        Application app = new Application();

        glWindow.addGLEventListener(app);
        glWindow.addKeyListener(app);
    }

    private boolean debugOpenGL = false, vBlank = false, glFinishHack = true;
    public boolean showCubes = true;

    public Vec2i windowSize = new Vec2i(1280, 720), renderSize = new Vec2i();

    public IVRSystem hmd;
    private IVRCompositor_FnTable compositor;

    private TrackedDevicePose_t.ByReference trackedDevicePosesReference = new TrackedDevicePose_t.ByReference();
    public TrackedDevicePose_t[] trackedDevicePose
            = (TrackedDevicePose_t[]) trackedDevicePosesReference.toArray(VR.k_unMaxTrackedDeviceCount);

    public FramebufferDesc[] eyeDesc = new FramebufferDesc[VR.EVREye.Max];
    private Texture_t[] eyeTexture = {new Texture_t(), new Texture_t()};

    private Scene scene;
    private Distortion distortion;
    public AxisLineControllers axisLineControllers;
    private ModelsRender modelsRender;

    public FloatBuffer clearColor = GLBuffers.newDirectFloatBuffer(4), clearDepth = GLBuffers.newDirectFloatBuffer(1),
            matBuffer = GLBuffers.newDirectFloatBuffer(16);

    public Mat4[] projection = new Mat4[VR.EVREye.Max], eyePos = new Mat4[VR.EVREye.Max],
            mat4DevicePose = new Mat4[VR.k_unMaxTrackedDeviceCount];
    public Mat4 hmdPose = new Mat4(), vp = new Mat4();

    public boolean[] rbShowTrackedDevice = new boolean[VR.k_unMaxTrackedDeviceCount];

    private char[] devClassChar = new char[VR.k_unMaxTrackedDeviceCount];

    private IntBuffer errorBuffer = GLBuffers.newDirectIntBuffer(1);

    public int trackedControllerCount = 0, trackedControllerCount_Last = -1, validPoseCount = 0,
            validPoseCount_Last = -1;

    private String poseClasses;

    public Application() {

        Display display = NewtFactory.createDisplay(null);
        Screen screen = NewtFactory.createScreen(display, 0);
        GLProfile glProfile = GLProfile.get(GLProfile.GL4);
        GLCapabilities glCapabilities = new GLCapabilities(glProfile);
        glWindow = GLWindow.create(screen, glCapabilities);

        glWindow.setSize(windowSize.x, windowSize.y);
        glWindow.setPosition(700, 100);
        glWindow.setUndecorated(false);
        glWindow.setAlwaysOnTop(false);
        glWindow.setFullscreen(false);
        glWindow.setPointerVisible(true);
        glWindow.confinePointer(false);
        glWindow.setTitle("hellovr_jogl");

        if (debugOpenGL) {
            glWindow.setContextCreationFlags(GLContext.CTX_OPTION_DEBUG);
        }
        glWindow.setVisible(true);
        if (debugOpenGL) {
            glWindow.getContext().addGLDebugListener(new GlDebugOutput());
        }

        glWindow.setAutoSwapBufferMode(false);
        animator = new Animator(glWindow);
        animator.start();
    }

    @Override
    public void init(GLAutoDrawable drawable) {

        // Loading the SteamVR Runtime
        hmd = VR.VR_Init(errorBuffer, VR.EVRApplicationType.VRApplication_Scene);

        if (errorBuffer.get(0) != VR.EVRInitError.VRInitError_None) {
            hmd = null;
            String s = "Unable to init VR runtime: " + VR.VR_GetVRInitErrorAsEnglishDescription(errorBuffer.get(0));
            throw new Error("VR_Init Failed, " + s);
        }

        GL4 gl4 = drawable.getGL().getGL4();

        gl4.setSwapInterval(vBlank ? 1 : 0);

        if (!initGL(gl4)) {
            System.err.println("Unable to initialize OpenGL!");
            quit();
        }

        if (!initCompositor()) {
            System.err.println("Failed to initialize VR Compositor!");
            quit();
        }

        checkError(gl4, "init");
    }

    private boolean initGL(GL4 gl4) {

        if (debugOpenGL) {

            gl4.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, 0, null, true);
            gl4.glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        }

        setupScene(gl4); // setupTextureMaps() inside
        setupCameras();
        setupStereoRenderTargets(gl4);
        setupDistortion(gl4);

        setupRenderModels(gl4);

        axisLineControllers = new AxisLineControllers(gl4);

        IntStreamEx.range(mat4DevicePose.length).forEach(mat -> mat4DevicePose[mat] = new Mat4());

        return true;
    }

    private void setupScene(GL4 gl4) {
        if (hmd == null) {
            return;
        }
        scene = new Scene(gl4);
    }

    private void setupCameras() {
        for (int eye = 0; eye < VR.EVREye.Max; eye++) {
            projection[eye] = getHmdMatrixProjection(eye);
            eyePos[eye] = getHmdMatrixPoseEye(eye);
        }
    }

    private Mat4 getHmdMatrixProjection(int eye) {
        if (hmd == null) {
            return new Mat4();
        }
        float nearClip = 0.1f, farClip = 30.0f;
        return new Mat4(hmd.GetProjectionMatrix.apply(eye, nearClip, farClip, VR.EGraphicsAPIConvention.API_OpenGL));
    }

    private Mat4 getHmdMatrixPoseEye(int eye) {
        if (hmd == null) {
            return new Mat4();
        }
        return new Mat4(hmd.GetEyeToHeadTransform.apply(eye)).inverse();
    }

    private boolean setupStereoRenderTargets(GL4 gl4) {
        if (hmd == null) {
            return false;
        }
        IntBuffer width = GLBuffers.newDirectIntBuffer(1), height = GLBuffers.newDirectIntBuffer(1);

        hmd.GetRecommendedRenderTargetSize.apply(width, height);
        renderSize.set(width.get(0), height.get(0));

        IntStreamEx.range(VR.EVREye.Max).forEach(eye -> eyeDesc[eye] = new FramebufferDesc(gl4, renderSize));

        BufferUtils.destroyDirectBuffer(width);
        BufferUtils.destroyDirectBuffer(height);

        return true;
    }

    private void setupDistortion(GL4 gl4) {
        if (hmd == null) {
            return;
        }
        distortion = new Distortion(gl4, hmd);
    }

    private void setupRenderModels(GL4 gl4) {
        modelsRender = new ModelsRender(gl4);
        modelsRender.setupRenderModels(gl4, hmd);
    }

    private boolean initCompositor() {

        compositor = new IVRCompositor_FnTable(VR.VR_GetGenericInterface(VR.IVRCompositor_Version, errorBuffer));

        if (compositor == null || errorBuffer.get(0) != VR.EVRInitError.VRInitError_None) {
            System.err.println("Compositor initialization failed. See log file for details");
            return false;
        }
        return true;
    }

    @Override
    public void display(GLAutoDrawable drawable) {

        GL4 gl4 = drawable.getGL().getGL4();

        handleInput(gl4);
//         for now as fast as possible
        if (hmd != null) {

            axisLineControllers.update(gl4, this);  // = DrawControllers();
            renderStereoTargets(gl4);
            distortion.render(gl4, this);

            for (int eye = 0; eye < VR.EVREye.Max; eye++) {
                eyeTexture[eye].set(eyeDesc[eye].textureName.get(FramebufferDesc.Target.RESOLVE),
                        VR.EGraphicsAPIConvention.API_OpenGL, VR.EColorSpace.ColorSpace_Gamma);
                compositor.Submit.apply(eye, eyeTexture[eye], null, VR.EVRSubmitFlags.Submit_Default);
            }
        }

        if (vBlank && glFinishHack) {
            /**
             * $ HACKHACK. From gpuview profiling, it looks like there is a bug where two renders and a present happen
             * right before and after the vsync causing all kinds of jittering issues. This glFinish() appears to
             * clear that up. Temporary fix while I try to get nvidia to investigate this problem. 1/29/2014 mikesart.
             */
            gl4.glFinish();
        }

        // SwapWindow
        {
            glWindow.swapBuffers();
        }
        // Clear
        {
            /**
             * We want to make sure the glFinish waits for the entire present to
             * complete, not just the submission of the command. So, we do a
             * clear here right here so the glFinish will wait fully for the
             * swap.
             */
            gl4.glClearBufferfv(GL_COLOR, 0, clearColor);
        }
        // Flush and wait for swap.
        if (vBlank) {
            gl4.glFlush();
            gl4.glFinish();
        }

        // Spew out the controller and pose count whenever they change.
        if (trackedControllerCount != trackedControllerCount_Last || validPoseCount != validPoseCount_Last) {

            validPoseCount_Last = validPoseCount;
            trackedControllerCount_Last = trackedControllerCount;

            System.out.println("PoseCount: " + validPoseCount + "(" + poseClasses + ")" + ", Controllers: "
                    + trackedControllerCount);
        }
        updateHMDMatrixPose();

        checkError(gl4, "display");
    }

    private void renderStereoTargets(GL4 gl4) {

        clearColor.put(0, 0.15f).put(1, 0.15f).put(2, 0.18f).put(3, 1.0f);  // nice background color, but not black
        clearDepth.put(0, 1.0f);

        for (int eye = 0; eye < VR.EVREye.Max; eye++) {

            gl4.glEnable(GL_MULTISAMPLE);

            gl4.glBindFramebuffer(GL_FRAMEBUFFER, eyeDesc[eye].framebufferName.get(FramebufferDesc.Target.RENDER));

            gl4.glViewport(0, 0, renderSize.x, renderSize.y);

            calcCurrentViewProjectionMatrix(eye);

            scene.render(gl4, this, eye);

            axisLineControllers.render(gl4, this);  // draw the controller axis lines

            modelsRender.render(gl4, this);

            gl4.glBindFramebuffer(GL_FRAMEBUFFER, 0);

            gl4.glDisable(GL_MULTISAMPLE);

            gl4.glBindFramebuffer(GL_READ_FRAMEBUFFER, eyeDesc[eye].framebufferName.get(FramebufferDesc.Target.RENDER));
            gl4.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, eyeDesc[eye].framebufferName.get(FramebufferDesc.Target.RESOLVE));

            gl4.glBlitFramebuffer(0, 0, renderSize.x, renderSize.y, 0, 0, renderSize.x, renderSize.y,
                    GL_COLOR_BUFFER_BIT,
                    GL_LINEAR);
        }
        gl4.glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        gl4.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    }

    private void calcCurrentViewProjectionMatrix(int eye) {
        projection[eye].mul(eyePos[eye], vp).mul(hmdPose).toDfb(matBuffer);
    }

    private void updateHMDMatrixPose() {
        if (hmd == null) {
            return;
        }
        compositor.WaitGetPoses.apply(trackedDevicePosesReference, VR.k_unMaxTrackedDeviceCount, null, 0);

        validPoseCount = 0;
        poseClasses = "";

        for (int device = 0; device < VR.k_unMaxTrackedDeviceCount; device++) {

            if (trackedDevicePose[device].bPoseIsValid != 0) {

                validPoseCount++;
                mat4DevicePose[device].set(trackedDevicePose[device].mDeviceToAbsoluteTracking);

                if (devClassChar[device] == 0) {

                    switch (hmd.GetTrackedDeviceClass.apply(device)) {

                        case VR.ETrackedDeviceClass.TrackedDeviceClass_Controller:
                            devClassChar[device] = 'C';
                            break;

                        case VR.ETrackedDeviceClass.TrackedDeviceClass_HMD:
                            devClassChar[device] = 'H';
                            break;

                        case VR.ETrackedDeviceClass.TrackedDeviceClass_Invalid:
                            devClassChar[device] = 'I';
                            break;

                        case VR.ETrackedDeviceClass.TrackedDeviceClass_Other:
                            devClassChar[device] = 'O';
                            break;

                        case VR.ETrackedDeviceClass.TrackedDeviceClass_TrackingReference:
                            devClassChar[device] = 'T';
                            break;

                        default:
                            devClassChar[device] = '?';
                            break;
                    }
                    poseClasses += devClassChar[device];
                }
            }
        }
        if (trackedDevicePose[VR.k_unTrackedDeviceIndex_Hmd].bPoseIsValid != 0) {
            mat4DevicePose[VR.k_unTrackedDeviceIndex_Hmd].inverse(hmdPose);
        }
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {

        if (hmd != null) {
            VR.VR_Shutdown();
            hmd = null;
        }

        GL4 gl4 = drawable.getGL().getGL4();

        modelsRender.dispose(gl4);
        scene.dispose(gl4);
        axisLineControllers.dispose(gl4);
        distortion.dispose(gl4);

        IntStreamEx.range(VR.EVREye.Max).forEach(eye -> eyeDesc[eye].dispose(gl4));

        BufferUtils.destroyDirectBuffer(clearColor);
        BufferUtils.destroyDirectBuffer(clearDepth);
        BufferUtils.destroyDirectBuffer(errorBuffer);
        BufferUtils.destroyDirectBuffer(matBuffer);

        System.exit(0);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE:
                quit();
                break;
            case KeyEvent.VK_C:
                showCubes = !showCubes;
                break;
        }
    }

    void handleInput(GL4 gl4) {

        // Process SteamVR events
        VREvent_t event = new VREvent_t();
        while (hmd.PollNextEvent.apply(event, event.size())) {

            processVREvent(gl4, event);
        }

        // Process SteamVR controller state
        for (int device = 0; device < VR.k_unMaxTrackedDeviceCount; device++) {

            VRControllerState_t state = new VRControllerState_t();

            if (hmd.GetControllerState.apply(device, state)) {

                rbShowTrackedDevice[device] = state.ulButtonPressed == 0;

                // let's test haptic impulse too..
                if (state.ulButtonPressed != 0) {
                    // apparently only axis ID 0 works, maximum duration value is 3999
                    hmd.TriggerHapticPulse.apply(device, 0, (short) 3999);
                }

            }
        }
    }

    void processVREvent(GL4 gl4, VREvent_t event) {

        switch (event.eventType) {

            case VR.EVREventType.VREvent_TrackedDeviceActivated:
                //TODO | ask giuseppe for gl
                modelsRender.setupRenderModelForTrackedDevice(gl4, event.trackedDeviceIndex, hmd);
                System.out.println("Device %u attached. Setting up render model.\n" + event.trackedDeviceIndex);
                break;

            case VR.EVREventType.VREvent_TrackedDeviceDeactivated:
                System.out.println("Device %u detached.\n" + event.trackedDeviceIndex);
                break;

            case VR.EVREventType.VREvent_TrackedDeviceUpdated:
                System.out.println("Device %u updated.\n" + event.trackedDeviceIndex);
                break;
        }
    }

    private void quit() {
        animator.remove(glWindow);
        glWindow.destroy();
        System.exit(0);
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    private void checkError(GL4 gl4, String string) {
        int error = gl4.glGetError();
        if (error != GL_NO_ERROR) {
            System.err.println("error " + error + ", " + string);
        }
    }

}
