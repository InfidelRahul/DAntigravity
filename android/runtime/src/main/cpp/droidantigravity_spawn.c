#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <poll.h>
#include <termios.h>

#if defined(__has_include)
#  if __has_include(<pty.h>)
#    include <pty.h>
#  endif
#endif

// Forward declaration if openpty is in libc
extern int openpty(int *amaster, int *aslave, char *name, const struct termios *termp, const struct winsize *winp);

#include <android/log.h>
#define LOG_TAG "DroidAntigravitySpawn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void close_pair(int pair[2]) {
    close(pair[0]);
    close(pair[1]);
}

static jintArray native_spawn_streams_impl(JNIEnv *env, jobjectArray java_argv,
                                           jobjectArray java_env, jstring java_cwd,
                                           jstring java_stdout, jstring java_stderr) {
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        free(argv);
        free(envp);
        return NULL;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);

    const char *stdout_utf = (*env)->GetStringUTFChars(env, java_stdout, NULL);
    char *stdout_path = strdup(stdout_utf);
    (*env)->ReleaseStringUTFChars(env, java_stdout, stdout_utf);

    char *stderr_path = NULL;
    if (java_stderr != NULL) {
        const char *stderr_utf = (*env)->GetStringUTFChars(env, java_stderr, NULL);
        stderr_path = strdup(stderr_utf);
        (*env)->ReleaseStringUTFChars(env, java_stderr, stderr_utf);
    }

    int in_pipe[2];
    if (pipe(in_pipe) != 0) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(argv); free(envp); free(cwd); free(stdout_path); free(stderr_path);
        return NULL;
    }

    pid_t pid = fork();
    if (pid == 0) {
        setpgid(0, 0);
        close(in_pipe[1]);

        int stdout_fd = open(stdout_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
        if (stdout_fd < 0) {
            dprintf(STDERR_FILENO, "Failed to open stdout file %s: %s\n", stdout_path, strerror(errno));
            _exit(126);
        }

        int stderr_fd = -1;
        if (stderr_path != NULL) {
            stderr_fd = open(stderr_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
        }
        if (stderr_fd < 0) {
            stderr_fd = stdout_fd;
        }

        dup2(in_pipe[0], STDIN_FILENO);
        dup2(stdout_fd, STDOUT_FILENO);
        dup2(stderr_fd, STDERR_FILENO);

        close(in_pipe[0]);
        if (stdout_fd != STDOUT_FILENO) close(stdout_fd);
        if (stderr_fd >= 0 && stderr_fd != STDERR_FILENO && stderr_fd != stdout_fd) close(stderr_fd);

        if (chdir(cwd) != 0) {
            dprintf(STDERR_FILENO, "Failed to chdir to %s: %s\n", cwd, strerror(errno));
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "Native exec failed (%s): %s\n", argv[0], strerror(errno));
        _exit(127);
    }

    if (pid > 0) {
        setpgid(pid, pid);
    }
    close(in_pipe[0]);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd); free(stdout_path); free(stderr_path);

    if (pid < 0) {
        close_pair(in_pipe);
        return NULL;
    }
    jint values[2] = {pid, in_pipe[1]};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

static jintArray native_spawn_pty_streams_impl(JNIEnv *env, jobjectArray java_argv,
                                               jobjectArray java_env, jstring java_cwd,
                                               jstring java_stdout, jstring java_stderr,
                                               jint cols, jint rows) {
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        free(argv);
        free(envp);
        return NULL;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);

    const char *stdout_utf = (*env)->GetStringUTFChars(env, java_stdout, NULL);
    char *stdout_path = strdup(stdout_utf);
    (*env)->ReleaseStringUTFChars(env, java_stdout, stdout_utf);

    char *stderr_path = NULL;
    if (java_stderr != NULL) {
        const char *stderr_utf = (*env)->GetStringUTFChars(env, java_stderr, NULL);
        stderr_path = strdup(stderr_utf);
        (*env)->ReleaseStringUTFChars(env, java_stderr, stderr_utf);
    }

    int master = -1, slave = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);

    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        LOGE("openpty failed: %s, falling back to standard pipe spawn", strerror(errno));
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(argv); free(envp); free(cwd); free(stdout_path); free(stderr_path);
        return native_spawn_streams_impl(env, java_argv, java_env, java_cwd, java_stdout, java_stderr);
    }

    // Initialize/truncate stdout file
    int init_fd = open(stdout_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (init_fd >= 0) close(init_fd);

    pid_t pid = fork();
    if (pid == 0) {
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);

        if (stderr_path != NULL) {
            int stderr_fd = open(stderr_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
            if (stderr_fd >= 0) {
                dup2(stderr_fd, STDERR_FILENO);
                close(stderr_fd);
            } else {
                dup2(slave, STDERR_FILENO);
            }
        } else {
            dup2(slave, STDERR_FILENO);
        }

        close(slave);

        if (chdir(cwd) != 0) {
            dprintf(STDERR_FILENO, "Failed to chdir to %s: %s\n", cwd, strerror(errno));
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "Native exec failed (%s): %s\n", argv[0], strerror(errno));
        _exit(127);
    }

    close(slave);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd); free(stderr_path);

    if (pid < 0) {
        close(master);
        free(stdout_path);
        return NULL;
    }

    /*
     * The PTY master is a single byte stream. Do not create a second reader
     * for diagnostics: dup(master) shares the same open file description and
     * a reader on it consumes bytes that the interactive owner needs.
     * Diagnostic capture is performed by the Kotlin PTY owner instead.
     */
    if (stdout_path) {
        free(stdout_path);
    }


    jint values[2] = {pid, master};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}


static jintArray native_spawn_pty_interactive_impl(JNIEnv *env, jobjectArray java_argv,
                                                    jobjectArray java_env, jstring java_cwd,
                                                    jint cols, jint rows) {
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) { free(argv); free(envp); return NULL; }

    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }

    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);

    int master = -1, slave = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);

    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        LOGE("openpty interactive failed: %s", strerror(errno));
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(argv); free(envp); free(cwd);
        return NULL;
    }

    pid_t pid = fork();
    if (pid == 0) {
        close(master);
        if (setsid() < 0) _exit(126);
        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        if (chdir(cwd) != 0) {
            dprintf(STDERR_FILENO, "Failed to chdir to %s: %s\n", cwd, strerror(errno));
            _exit(126);
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "Native exec failed (%s): %s\n", argv[0], strerror(errno));
        _exit(127);
    }

    close(slave);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd);

    if (pid < 0) {
        close(master);
        return NULL;
    }
    setpgid(pid, pid);

    jint values[2] = {pid, master};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

static jint native_wait_for_impl(jint pid, jboolean no_hang) {
    int status = 0;
    pid_t value = waitpid(pid, &status, no_hang ? WNOHANG : 0);
    if (value == 0) return -2; // Still running
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

static jint native_kill_impl(jint pid, jint signal) {
    int result = kill(-pid, signal);
    if (result != 0 && errno == ESRCH) {
        result = kill(pid, signal);
    }
    return result;
}

static jint native_write_impl(JNIEnv *env, jint fd, jbyteArray java_data) {
    if (fd < 0 || !java_data) return -1;
    jsize len = (*env)->GetArrayLength(env, java_data);
    if (len <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, java_data, NULL);
    ssize_t written = write(fd, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, java_data, bytes, JNI_ABORT);
    return (jint)written;
}

static jint native_close_impl(jint fd) {
    if (fd >= 0) {
        return close(fd);
    }
    return 0;
}

// JNI exports for com.droidantigravity.runtime.NativeSpawn
JNIEXPORT jintArray JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                    jobjectArray java_env, jstring java_cwd,
                                                    jstring java_output) {
    (void)self;
    return native_spawn_streams_impl(env, java_argv, java_env, java_cwd, java_output, NULL);
}

JNIEXPORT jintArray JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_spawnWithStreams(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                               jobjectArray java_env, jstring java_cwd,
                                                               jstring java_stdout, jstring java_stderr) {
    (void)self;
    return native_spawn_streams_impl(env, java_argv, java_env, java_cwd, java_stdout, java_stderr);
}

JNIEXPORT jintArray JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_spawnPty(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                        jobjectArray java_env, jstring java_cwd,
                                                        jstring java_output, jint cols, jint rows) {
    (void)self;
    return native_spawn_pty_streams_impl(env, java_argv, java_env, java_cwd, java_output, NULL, cols, rows);
}

JNIEXPORT jintArray JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_spawnPtyWithStreams(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                                  jobjectArray java_env, jstring java_cwd,
                                                                  jstring java_stdout, jstring java_stderr,
                                                                  jint cols, jint rows) {
    (void)self;
    return native_spawn_pty_streams_impl(env, java_argv, java_env, java_cwd, java_stdout, java_stderr, cols, rows);
}

JNIEXPORT jint JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_write(JNIEnv *env, jobject self, jint fd, jbyteArray data) {
    (void)self;
    return native_write_impl(env, fd, data);
}

JNIEXPORT jintArray JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_spawnPtyInteractive(JNIEnv *env, jobject self,
                                                                   jobjectArray java_argv,
                                                                   jobjectArray java_env, jstring java_cwd,
                                                                   jint cols, jint rows) {
    (void)self;
    return native_spawn_pty_interactive_impl(env, java_argv, java_env, java_cwd, cols, rows);
}

JNIEXPORT jint JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_read(JNIEnv *env, jobject self, jint fd, jbyteArray java_data) {
    (void)self;
    if (fd < 0 || !java_data) return -1;
    jsize len = (*env)->GetArrayLength(env, java_data);
    if (len <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, java_data, NULL);
    struct pollfd pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.fd = fd;
    pfd.events = POLLIN | POLLHUP | POLLERR;

    int ready;
    do {
        ready = poll(&pfd, 1, 100);
    } while (ready < 0 && errno == EINTR);

    if (ready == 0) {
        (*env)->ReleaseByteArrayElements(env, java_data, bytes, JNI_ABORT);
        return 0;
    }
    if (ready < 0) {
        int error = errno;
        (*env)->ReleaseByteArrayElements(env, java_data, bytes, JNI_ABORT);
        return -error;
    }

    ssize_t n;
    do { n = read(fd, bytes, (size_t)len); } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, java_data, bytes, JNI_ABORT);
    if (n < 0) return -errno;
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_resizePty(JNIEnv *env, jobject self, jint fd, jint cols, jint rows) {
    (void)env; (void)self;
    if (fd < 0) return -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    return ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    return native_wait_for_impl(pid, no_hang);
}

JNIEXPORT jint JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    return native_kill_impl(pid, signal);
}

JNIEXPORT jint JNICALL
Java_com_droidantigravity_runtime_NativeSpawn_close(JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    return native_close_impl(fd);
}

// Backwards compatibility JNI exports for com.linuxdroid.core.process.NativeSpawn
JNIEXPORT jintArray JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                   jobjectArray java_env, jstring java_cwd,
                                                   jstring java_output) {
    (void)self;
    return native_spawn_streams_impl(env, java_argv, java_env, java_cwd, java_output, NULL);
}

JNIEXPORT jintArray JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_spawnPty(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                      jobjectArray java_env, jstring java_cwd,
                                                      jstring java_output, jint cols, jint rows) {
    (void)self;
    return native_spawn_pty_streams_impl(env, java_argv, java_env, java_cwd, java_output, NULL, cols, rows);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_write(JNIEnv *env, jobject self, jint fd, jbyteArray data) {
    (void)self;
    return native_write_impl(env, fd, data);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    return native_wait_for_impl(pid, no_hang);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    return native_kill_impl(pid, signal);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_close(JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    return native_close_impl(fd);
}
