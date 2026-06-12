package app.grapheneos.goscompat.checks;

import android.net.Uri;

public final class GosCompatContract {
    public static final class App {
        public static final String PACKAGE_NAME = "app.grapheneos.goscompat.checks";

        private App() {
        }
    }

    public static final class MapsScan {
        public static final String PROCESS = App.PACKAGE_NAME + ":maps_scan";
        public static final String CRASH_ACTIVITY = App.PACKAGE_NAME + "/.MapsScanCrashActivity";
        public static final String AUTHORITY = App.PACKAGE_NAME + ".maps_scan_provider";
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
        public static final String RESULT_FILE = "maps_scan_result.properties";

        public static final class Extra {
            public static final String MODE = "maps_scan_mode";
            public static final String TOKEN = "maps_scan_token";

            private Extra() {
            }
        }

        public static final class Mode {
            public static final String DIRECT = "direct";
            public static final String REFLECTIVE = "reflective";

            private Mode() {
            }
        }

        public static final class Method {
            public static final String RUN_CHECK = "run_maps_scan_check";
            public static final String RUN_DIRECT_CHECK = "run_direct_maps_scan_check";
            public static final String RUN_REFLECTIVE_CHECK = "run_reflective_maps_scan_check";
            public static final String GET_RESULT = "get_maps_scan_result";
            public static final String CLEAR_RESULT = "clear_maps_scan_result";

            private Method() {
            }
        }

        public static final class Key {
            public static final String RESULT_AVAILABLE = "maps_scan_result_available";
            public static final String COMPLETED = "maps_scan_completed";
            public static final String WORKER_THREAD = "maps_scan_worker_thread";
            public static final String SELECTED_RANGES = "maps_scan_selected_ranges";
            public static final String SCANNED_BYTES = "maps_scan_scanned_bytes";
            public static final String CALLER_TID = "maps_scan_caller_tid";
            public static final String WORKER_TID = "maps_scan_worker_tid";
            public static final String STATUS_TEXT = "status_text";
            public static final String SUMMARY = "summary";
            public static final String DETAILS = "maps_scan_details";
            public static final String ERRORS = "maps_scan_errors";

            private Key() {
            }
        }

        private MapsScan() {
        }
    }

    public static final class WebViewUaStartup {
        public static final String ACTIVITY =
                App.PACKAGE_NAME + "/.webviewua.WebViewUaStartupActivity";
        public static final String RESULT_FILE = "webview_ua_startup_result.properties";

        public static final class Extra {
            public static final String TOKEN = "webview_ua_startup_token";
            public static final String EXIT_PROCESS = "webview_ua_startup_exit_process";

            private Extra() {
            }
        }

        public static final class Key {
            public static final String RESULT_AVAILABLE =
                    "webview_ua_startup_result_available";
            public static final String COMPLETED = "webview_ua_startup_completed";
            public static final String WORKER_THREAD = "webview_ua_startup_worker_thread";
            public static final String UI_THREAD = "webview_ua_startup_ui_thread";
            public static final String MAIN_LOOPER = "webview_ua_startup_main_looper";
            public static final String WORKER_TID = "webview_ua_startup_worker_tid";
            public static final String UI_TID = "webview_ua_startup_ui_tid";
            public static final String DURATION_MS = "webview_ua_startup_duration_ms";
            public static final String USER_AGENT_LENGTH =
                    "webview_ua_startup_user_agent_length";
            public static final String ERROR = "webview_ua_startup_error";

            private Key() {
            }
        }

        private WebViewUaStartup() {
        }
    }

    public static final class DmaBufRelease {
        public static final String PROCESS = App.PACKAGE_NAME + ":dmabuf_release";
        public static final String ACTIVITY = App.PACKAGE_NAME + "/.dmabuf.DmaBufReleaseActivity";
        public static final String RESULT_FILE = "dmabuf_release_result.properties";
        public static final int DEFAULT_ITERATIONS = 4;
        public static final int VFRAME_SECURE_DIRECT_WIDTH = 8192;
        public static final int VFRAME_SECURE_DIRECT_HEIGHT = 8192;
        public static final int VFRAME_SECURE_DIRECT_COUNT = 1;
        public static final int VSTREAM_SECURE_DIRECT_WIDTH = 1024;
        public static final int VSTREAM_SECURE_DIRECT_HEIGHT = 1024;
        public static final int VSTREAM_SECURE_DIRECT_COUNT = 1;
        public static final int SECURE_CHUNK_HEAP_ONE_CHUNK_WIDTH = 128;
        public static final int SECURE_CHUNK_HEAP_ONE_CHUNK_HEIGHT = 128;
        public static final int SECURE_CHUNK_HEAP_ONE_CHUNK_COUNT = 1;
        public static final int PROTECTED_EGL_WIDTH = 8192;
        public static final int PROTECTED_EGL_HEIGHT = 8192;
        public static final int PROTECTED_EGL_RESOURCE_COUNT = 2;

        public static final class Extra {
            public static final String TOKEN = "dmabuf_release_token";
            public static final String MODE = "dmabuf_release_mode";
            public static final String HEAP_NAME = "dmabuf_release_heap_name";
            public static final String WIDTH = "dmabuf_release_width";
            public static final String HEIGHT = "dmabuf_release_height";
            public static final String BUFFER_COUNT = "dmabuf_release_buffer_count";
            public static final String ITERATIONS = "dmabuf_release_iterations";
            public static final String RELEASE_AFTER_READY =
                    "dmabuf_release_release_after_ready";

            private Extra() {
            }
        }

        public static final class Mode {
            public static final String SECURE_CHUNK_HEAP_DIRECT =
                    "secure_chunk_heap_direct";
            public static final String PROTECTED_EGL = "protected_egl";

            private Mode() {
            }
        }

        public static final class Heap {
            public static final String VFRAME_SECURE = "vframe-secure";
            public static final String VSTREAM_SECURE = "vstream-secure";

            private Heap() {
            }
        }

        public static final class Key {
            public static final String RESULT_AVAILABLE = "dmabuf_release_result_available";
            public static final String READY = "dmabuf_release_ready";
            public static final String UNSUPPORTED = "dmabuf_release_unsupported";
            public static final String MODE = "dmabuf_release_mode";
            public static final String WIDTH = "dmabuf_release_width";
            public static final String HEIGHT = "dmabuf_release_height";
            public static final String REQUESTED_BUFFERS =
                    "dmabuf_release_requested_buffers";
            public static final String ALLOCATED_BUFFERS =
                    "dmabuf_release_allocated_buffers";
            public static final String ITERATIONS = "dmabuf_release_iterations";
            public static final String PID = "dmabuf_release_pid";
            public static final String TID = "dmabuf_release_tid";
            public static final String PROTECTED_CONTENT =
                    "dmabuf_release_protected_content";
            public static final String RELEASED = "dmabuf_release_released";
            public static final String HEAP_PATH = "dmabuf_release_heap_path";
            public static final String HEAP_NAME = "dmabuf_release_heap_name";
            public static final String ALLOCATOR = "dmabuf_release_allocator";
            public static final String ALLOCATION = "dmabuf_release_allocation";
            public static final String ERROR = "dmabuf_release_error";

            private Key() {
            }
        }

        private DmaBufRelease() {
        }
    }

    public static final class SecureSpawn {
        public static final String PACKAGE_NAME = "app.grapheneos.goscompat.securespawn";
        public static final String ACTIVITY_CLASS = PACKAGE_NAME + ".SecureSpawnActivity";

        private SecureSpawn() {
        }
    }

    private GosCompatContract() {
    }
}
