// SENTINEL.exe: the Windows launcher for the SENTINEL desktop app.
//
// It lives in %LOCALAPPDATA%\SENTINEL next to the files the installer (windows/install.ps1) set up:
//
//	uv\uv.exe      the uv package manager
//	python\        the Python 3.12 that uv installed
//	poisoner\      the app (Python package aipoisoner)
//	env\           its Python environment
//	models\, hf\   the downloaded AI models
//
// It starts "aipoisoner app" with no console window and passes its own arguments on (for example
// --samples "<Desktop>\SENTINEL Test Files"). Output goes to sentinel.log in the same folder.
// Build: GOOS=windows GOARCH=amd64 go build -ldflags "-H windowsgui -s -w" -o SENTINEL.exe
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const createNoWindow = 0x08000000

func messageBox(title, text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	proc := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(title)
	proc.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10) // MB_ICONERROR
}

func tail(path string, n int) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	lines := strings.Split(strings.TrimSpace(string(b)), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

func main() {
	exe, err := os.Executable()
	if err != nil {
		messageBox("SENTINEL", "Cannot find where SENTINEL is installed.")
		os.Exit(1)
	}
	dir := filepath.Dir(exe)
	uv := filepath.Join(dir, "uv", "uv.exe")
	project := filepath.Join(dir, "poisoner")
	logPath := filepath.Join(dir, "sentinel.log")

	if _, err := os.Stat(uv); err != nil {
		messageBox("SENTINEL", "SENTINEL is not fully installed (uv.exe is missing).\n\nRun the installer again:\nirm https://github.com/YajatGhule/ai-poisoner/releases/download/v0.5.0-alpha/install.ps1 | iex")
		os.Exit(1)
	}

	logf, err := os.Create(logPath)
	if err != nil {
		logf = nil
	}
	args := append([]string{"run", "--project", project, "--no-dev", "--frozen", "aipoisoner", "app"}, os.Args[1:]...)
	cmd := exec.Command(uv, args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(),
		"UV_PYTHON_INSTALL_DIR="+filepath.Join(dir, "python"),
		"UV_CACHE_DIR="+filepath.Join(dir, "cache"),
		"UV_PROJECT_ENVIRONMENT="+filepath.Join(dir, "env"),
		"UV_PYTHON_PREFERENCE=only-managed",
		"SENTINEL_MODELS="+filepath.Join(dir, "models"),
		"HF_HOME="+filepath.Join(dir, "hf"),
		"PYTHONUNBUFFERED=1",
		"PYTHONIOENCODING=utf-8",
	)
	if logf != nil {
		cmd.Stdout = logf
		cmd.Stderr = logf
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}

	start := time.Now()
	err = cmd.Run()
	if logf != nil {
		logf.Close()
	}
	if err != nil {
		msg := fmt.Sprintf("SENTINEL stopped with an error after %d seconds.\n\nLast lines of %s:\n\n%s",
			int(time.Since(start).Seconds()), logPath, tail(logPath, 12))
		messageBox("SENTINEL", msg)
		os.Exit(1)
	}
}
