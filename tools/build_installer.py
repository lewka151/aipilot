#!/usr/bin/env python3
"""
Windows installer builder for AIPilot (and optional Platforma151 preset).
Uses the built-in Windows .NET Framework C# compiler (csc.exe).

Examples:
  python tools/build_installer.py
  python tools/build_installer.py
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
DIST_DIR = ROOT_DIR / "dist"
VERSION_FILE = ROOT_DIR / "build" / "libs" / "version.txt"


@dataclass
class InstallerSpec:
    app_title: str
    app_exe: str
    image_dir: Path
    output_exe: Path
    icon_path: Path
    install_folder: str
    shortcut_name: str
    uninstall_key: str
    subtitle: str
    launch_after: str
    publisher: str
    fallback_exe: str
    gradle_task: str
    cfg_name: str



def aipilot_spec() -> InstallerSpec:
    return InstallerSpec(
        app_title="AIPilot",
        app_exe="AIPilot.exe",
        image_dir=DIST_DIR / "AIPilot",
        output_exe=DIST_DIR / "AIPilot-Setup.exe",
        icon_path=ROOT_DIR / "assets" / "icons" / "aipilot.ico",
        install_folder="AIPilot",
        shortcut_name="AIPilot",
        uninstall_key="AIPilot",
        subtitle="Оркестратор Qwen и DeepSeek, проекты и PowerShell",
        launch_after="Запустить AIPilot после установки",
        publisher="AIPilot",
        fallback_exe="AIPilot.exe",
        gradle_task="packageApp",
        cfg_name="AIPilot.cfg",
    )


def platforma_spec() -> InstallerSpec:
    return InstallerSpec(
        app_title="Platforma151",
        app_exe="Platforma151.exe",
        image_dir=DIST_DIR / "Platforma151",
        output_exe=DIST_DIR / "Platforma151-Setup.exe",
        icon_path=ROOT_DIR / "assets" / "icons" / "aipilot.ico",
        install_folder="Platforma151",
        shortcut_name="Platforma151",
        uninstall_key="Platforma151",
        subtitle="Облачные сохранения, трей, вход через сайт (квота 250 МБ)",
        launch_after="Запустить Platforma151 после установки",
        publisher="Platforma151",
        fallback_exe="Platforma151.exe",
        gradle_task="packageApp",
        cfg_name="Platforma151.cfg",
    )


def parse_args() -> InstallerSpec:
    p = argparse.ArgumentParser(description="Build a Windows setup EXE from a jpackage app-image")
    p.add_argument("--preset", choices=["aipilot", "platforma"], default="aipilot")
    p.add_argument("--name")
    p.add_argument("--exe")
    p.add_argument("--image-dir")
    p.add_argument("--output")
    p.add_argument("--icon")
    p.add_argument("--install-folder")
    p.add_argument("--shortcut-name")
    p.add_argument("--uninstall-key")
    p.add_argument("--subtitle")
    p.add_argument("--gradle-task")
    ns = p.parse_args()
    spec = aipilot_spec() if ns.preset == "aipilot" else platforma_spec()

    def path_or_default(value: str | None, default: Path) -> Path:
        if not value:
            return default
        pth = Path(value)
        return pth if pth.is_absolute() else ROOT_DIR / pth

    name = ns.name or spec.app_title
    exe = ns.exe or spec.app_exe
    return InstallerSpec(
        app_title=name,
        app_exe=exe,
        image_dir=path_or_default(ns.image_dir, spec.image_dir),
        output_exe=path_or_default(ns.output, spec.output_exe),
        icon_path=path_or_default(ns.icon, spec.icon_path),
        install_folder=ns.install_folder or spec.install_folder,
        shortcut_name=ns.shortcut_name or spec.shortcut_name,
        uninstall_key=ns.uninstall_key or spec.uninstall_key,
        subtitle=ns.subtitle or spec.subtitle,
        launch_after=f"Запустить {name} после установки",
        publisher=name,
        fallback_exe=exe,
        gradle_task=ns.gradle_task or spec.gradle_task,
        cfg_name=Path(exe).stem + ".cfg",
    )


def get_version() -> str:
    if VERSION_FILE.is_file():
        return VERSION_FILE.read_text(encoding="utf-8").strip()
    return "0.1.1"


def find_csc() -> Path:
    candidates = [
        Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"),
        Path(r"C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"),
    ]
    for c in candidates:
        if c.is_file():
            return c
    found = shutil.which("csc.exe")
    if found:
        return Path(found)
    raise RuntimeError("csc.exe not found. .NET Framework 4.5+ is required to build installer.")


def ensure_launcher_image(spec: InstallerSpec):
    exe = spec.image_dir / spec.app_exe
    cfg = spec.image_dir / "app" / spec.cfg_name
    if spec.image_dir.is_dir() and exe.is_file() and cfg.is_file():
        return

    print(f"[installer] Running gradle {spec.gradle_task}...")
    gradlew = ROOT_DIR / ("gradlew.bat" if os.name == "nt" else "gradlew")
    res = subprocess.run([str(gradlew), spec.gradle_task], cwd=str(ROOT_DIR))
    if res.returncode != 0 or not spec.image_dir.is_dir():
        raise RuntimeError(f"Failed to build app-image with gradle {spec.gradle_task}: exit code {res.returncode}")


def make_payload_zip(spec: InstallerSpec, zip_path: Path):
    print(f"[installer] Packing {spec.image_dir} into {zip_path.name}...")
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root, dirs, files in os.walk(spec.image_dir):
            for f in files:
                full = Path(root) / f
                rel = full.relative_to(spec.image_dir)
                zf.write(full, rel.as_posix())


def generate_installer_cs(spec: InstallerSpec, cs_path: Path, version: str):
    code = r'''using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace BunkerSurvival.Installer
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }
    }

    public class InstallerForm : Form
    {
        private const string AppVersion = "__APP_VERSION__";
        private const string AppTitle = "__APP_TITLE__";
        private const string AppExeName = "__APP_EXE__";

        private readonly TextBox pathBox;
        private readonly CheckBox desktopShortBox;
        private readonly CheckBox startMenuShortBox;
        private readonly CheckBox launchAfterBox;
        private readonly ProgressBar progressBar;
        private readonly Label statusLabel;
        private readonly Button installBtn;
        private readonly Button cancelBtn;
        private readonly Button browseBtn;

        private BackgroundWorker worker;
        private bool installFinished;

        public InstallerForm()
        {
            Text = "Установка " + AppTitle + " " + AppVersion;
            Width = 560;
            Height = 440;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(20, 24, 33);
            ForeColor = Color.FromArgb(226, 232, 240);
            Font = new Font("Segoe UI", 9.25f, FontStyle.Regular);

            try
            {
                Icon = Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location);
            }
            catch { }

            // Header banner
            var headerPanel = new Panel
            {
                Dock = DockStyle.Top,
                Height = 84,
                BackColor = Color.FromArgb(28, 33, 44),
                Padding = new Padding(20, 14, 20, 10)
            };

            var titleLbl = new Label
            {
                Text = AppTitle,
                Font = new Font("Segoe UI", 14f, FontStyle.Bold),
                ForeColor = Color.FromArgb(248, 250, 252),
                AutoSize = true,
                Location = new Point(20, 12)
            };

            var subLbl = new Label
            {
                Text = "__APP_SUBTITLE__",
                Font = new Font("Segoe UI", 9f, FontStyle.Regular),
                ForeColor = Color.FromArgb(148, 163, 184),
                AutoSize = true,
                Location = new Point(22, 44)
            };

            headerPanel.Controls.Add(titleLbl);
            headerPanel.Controls.Add(subLbl);
            Controls.Add(headerPanel);

            // Path row
            int y = 100;
            var pathLbl = new Label
            {
                Text = "Папка для установки:",
                ForeColor = Color.FromArgb(203, 213, 225),
                Location = new Point(24, y),
                AutoSize = true
            };
            Controls.Add(pathLbl);

            y += 24;
            string defaultDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "__INSTALL_FOLDER__");
            pathBox = new TextBox
            {
                Text = defaultDir,
                Location = new Point(24, y),
                Width = 390,
                Height = 26,
                BackColor = Color.FromArgb(15, 18, 26),
                ForeColor = Color.FromArgb(241, 245, 249),
                BorderStyle = BorderStyle.FixedSingle
            };
            Controls.Add(pathBox);

            browseBtn = new Button
            {
                Text = "Обзор…",
                Location = new Point(424, y - 1),
                Width = 96,
                Height = 28,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(39, 46, 62),
                ForeColor = Color.FromArgb(241, 245, 249),
                Cursor = Cursors.Hand
            };
            browseBtn.FlatAppearance.BorderColor = Color.FromArgb(58, 68, 90);
            browseBtn.Click += (s, e) =>
            {
                using (var fbd = new FolderBrowserDialog())
                {
                    fbd.SelectedPath = pathBox.Text;
                    fbd.Description = "Выберите папку для установки " + AppTitle;
                    if (fbd.ShowDialog(this) == DialogResult.OK)
                    {
                        pathBox.Text = fbd.SelectedPath;
                    }
                }
            };
            Controls.Add(browseBtn);

            // Options
            y += 42;
            desktopShortBox = new CheckBox
            {
                Text = "Создать ярлык на Рабочем столе",
                Checked = true,
                Location = new Point(26, y),
                AutoSize = true,
                ForeColor = Color.FromArgb(226, 232, 240)
            };
            Controls.Add(desktopShortBox);

            y += 26;
            startMenuShortBox = new CheckBox
            {
                Text = "Создать ярлык в меню «Пуск»",
                Checked = true,
                Location = new Point(26, y),
                AutoSize = true,
                ForeColor = Color.FromArgb(226, 232, 240)
            };
            Controls.Add(startMenuShortBox);

            y += 26;
            launchAfterBox = new CheckBox
            {
                Text = "__LAUNCH_AFTER__",
                Checked = true,
                Location = new Point(26, y),
                AutoSize = true,
                ForeColor = Color.FromArgb(226, 232, 240)
            };
            Controls.Add(launchAfterBox);

            // Progress & Status
            y += 36;
            statusLabel = new Label
            {
                Text = "Готов к установке",
                Location = new Point(24, y),
                Width = 496,
                Height = 20,
                ForeColor = Color.FromArgb(148, 163, 184)
            };
            Controls.Add(statusLabel);

            y += 24;
            progressBar = new ProgressBar
            {
                Location = new Point(24, y),
                Width = 496,
                Height = 18,
                Style = ProgressBarStyle.Blocks,
                Value = 0
            };
            Controls.Add(progressBar);

            // Bottom Buttons
            y += 36;
            installBtn = new Button
            {
                Text = "Установить",
                Location = new Point(300, y),
                Width = 120,
                Height = 32,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(37, 99, 235),
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
                Cursor = Cursors.Hand
            };
            installBtn.FlatAppearance.BorderSize = 0;
            installBtn.Click += (s, e) =>
            {
                if (installFinished)
                {
                    TryLaunchInstalled(pathBox.Text);
                    Close();
                    return;
                }
                StartInstall();
            };
            Controls.Add(installBtn);

            cancelBtn = new Button
            {
                Text = "Отмена",
                Location = new Point(428, y),
                Width = 92,
                Height = 32,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(39, 46, 62),
                ForeColor = Color.FromArgb(226, 232, 240),
                Cursor = Cursors.Hand
            };
            cancelBtn.FlatAppearance.BorderColor = Color.FromArgb(58, 68, 90);
            cancelBtn.Click += (s, e) => Close();
            Controls.Add(cancelBtn);
        }

        private void StartInstall()
        {
            string targetDir = pathBox.Text.Trim();
            if (string.IsNullOrEmpty(targetDir))
            {
                MessageBox.Show(this, "Укажите путь для установки.", "Ошибка", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            pathBox.Enabled = false;
            browseBtn.Enabled = false;
            desktopShortBox.Enabled = false;
            startMenuShortBox.Enabled = false;
            installBtn.Enabled = false;
            cancelBtn.Enabled = false;

            statusLabel.Text = "Подготовка к установке...";
            progressBar.Value = 5;

            worker = new BackgroundWorker
            {
                WorkerReportsProgress = true
            };
            worker.DoWork += (s, ev) =>
            {
                try
                {
                    Directory.CreateDirectory(targetDir);
                    StopRunningApp();

                    var asm = Assembly.GetExecutingAssembly();
                    using (var stream = asm.GetManifestResourceStream("Payload"))
                    {
                        if (stream == null)
                        {
                            throw new InvalidOperationException("Встроенный архив лаунчера не найден в установщике.");
                        }

                        using (var zip = new ZipArchive(stream, ZipArchiveMode.Read))
                        {
                            int count = zip.Entries.Count;
                            int i = 0;
                            foreach (var entry in zip.Entries)
                            {
                                i++;
                                string name = entry.FullName.Replace('/', Path.DirectorySeparatorChar).Replace('\\', Path.DirectorySeparatorChar);
                                string fullDest = Path.Combine(targetDir, name);
                                if (string.IsNullOrEmpty(entry.Name))
                                {
                                    Directory.CreateDirectory(fullDest);
                                    continue;
                                }
                                Directory.CreateDirectory(Path.GetDirectoryName(fullDest));
                                ExtractFile(entry, fullDest);
                                int pct = 10 + (int)((i / (double)count) * 75);
                                worker.ReportProgress(pct, "Распаковка: " + entry.Name);
                            }
                        }
                    }

                    worker.ReportProgress(90, "Создание ярлыков...");
                    string exePath = Path.Combine(targetDir, AppExeName);
                    if (!File.Exists(exePath))
                    {
                        string altExe = Path.Combine(targetDir, "__FALLBACK_EXE__");
                        if (File.Exists(altExe))
                        {
                            exePath = altExe;
                        }
                    }

                    if (desktopShortBox.Checked)
                    {
                        string desktopPath = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                        CreateShortcut(Path.Combine(desktopPath, "__SHORTCUT_NAME__.lnk"), exePath, targetDir);
                    }

                    if (startMenuShortBox.Checked)
                    {
                        string programs = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "__SHORTCUT_NAME__");
                        Directory.CreateDirectory(programs);
                        CreateShortcut(Path.Combine(programs, "__SHORTCUT_NAME__.lnk"), exePath, targetDir);
                    }

                    worker.ReportProgress(95, "Регистрация в системе...");
                    RegisterUninstall(targetDir, exePath);

                    worker.ReportProgress(100, "Установка успешно завершена!");
                }
                catch (Exception ex)
                {
                    ev.Result = ex;
                }
            };

            worker.ProgressChanged += (s, ev) =>
            {
                progressBar.Value = Math.Min(100, Math.Max(0, ev.ProgressPercentage));
                if (ev.UserState != null)
                {
                    statusLabel.Text = ev.UserState.ToString();
                }
            };

            worker.RunWorkerCompleted += (s, ev) =>
            {
                Exception ex = ev.Result as Exception;
                if (ex != null)
                {
                    statusLabel.ForeColor = Color.FromArgb(239, 68, 68);
                    statusLabel.Text = "Ошибка установки: " + ex.Message;
                    MessageBox.Show(this, "Ошибка установки:\n" + ex.Message, "Ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    installBtn.Enabled = true;
                    cancelBtn.Enabled = true;
                }
                else
                {
                    installFinished = true;
                    statusLabel.ForeColor = Color.FromArgb(74, 222, 128);
                    statusLabel.Text = "Установка завершена!";
                    if (launchAfterBox.Checked)
                    {
                        TryLaunchInstalled(pathBox.Text);
                        Close();
                        return;
                    }
                    installBtn.Text = "Запустить";
                    installBtn.Enabled = true;
                    cancelBtn.Text = "Закрыть";
                    cancelBtn.Enabled = true;
                }
            };

            worker.RunWorkerAsync();
        }

        private static void StopRunningApp()
        {
            try
            {
                string name = Path.GetFileNameWithoutExtension(AppExeName);
                foreach (var p in Process.GetProcessesByName(name))
                {
                    try
                    {
                        p.Kill();
                        p.WaitForExit(8000);
                    }
                    catch { }
                }
                Thread.Sleep(400);
            }
            catch { }
        }

        private static void ExtractFile(ZipArchiveEntry entry, string fullDest)
        {
            for (int attempt = 0; attempt < 6; attempt++)
            {
                try
                {
                    entry.ExtractToFile(fullDest, true);
                    return;
                }
                catch (IOException)
                {
                    try
                    {
                        string bak = fullDest + ".old";
                        if (File.Exists(bak))
                        {
                            File.Delete(bak);
                        }
                        if (File.Exists(fullDest))
                        {
                            File.Move(fullDest, bak);
                        }
                    }
                    catch { }
                    Thread.Sleep(250 * (attempt + 1));
                }
            }
            entry.ExtractToFile(fullDest, true);
        }

        private static void CreateShortcut(string shortcutPath, string targetPath, string workingDir)
        {
            try
            {
                Type t = Type.GetTypeFromProgID("WScript.Shell");
                if (t != null)
                {
                    dynamic shell = Activator.CreateInstance(t);
                    var link = shell.CreateShortcut(shortcutPath);
                    link.TargetPath = targetPath;
                    link.WorkingDirectory = workingDir;
                    string iconIco = Path.Combine(workingDir, "assets", "icons", "aipilot.ico");
                    if (!File.Exists(iconIco))
                    {
                        iconIco = Path.Combine(workingDir, "aipilot.ico");
                    }
                    link.IconLocation = File.Exists(iconIco) ? (iconIco + ",0") : (targetPath + ",0");
                    link.Description = AppTitle;
                    link.Save();
                }
            }
            catch { }
        }

        private static void RegisterUninstall(string installDir, string exePath)
        {
            try
            {
                using (var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\__UNINSTALL_KEY__"))
                {
                    if (key != null)
                    {
                        key.SetValue("DisplayName", AppTitle);
                        key.SetValue("DisplayVersion", AppVersion);
                        key.SetValue("Publisher", "__PUBLISHER__");
                        key.SetValue("DisplayIcon", exePath);
                        key.SetValue("InstallLocation", installDir);
                        key.SetValue("UninstallString", "cmd.exe /c rd /s /q \"" + installDir + "\"");
                    }
                }
            }
            catch { }
        }

        private static void TryLaunchInstalled(string installDir)
        {
            try
            {
                string exe = Path.Combine(installDir, AppExeName);
                if (!File.Exists(exe))
                {
                    exe = Path.Combine(installDir, "__FALLBACK_EXE__");
                }
                if (File.Exists(exe))
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = exe,
                        WorkingDirectory = installDir,
                        UseShellExecute = true
                    });
                }
            }
            catch { }
        }
    }
}
'''
    code = code.replace("__APP_VERSION__", version)
    code = code.replace("__APP_TITLE__", spec.app_title)
    code = code.replace("__APP_EXE__", spec.app_exe)
    code = code.replace("__APP_SUBTITLE__", spec.subtitle + " (v" + version + ")")
    code = code.replace("__INSTALL_FOLDER__", spec.install_folder)
    code = code.replace("__LAUNCH_AFTER__", spec.launch_after)
    code = code.replace("__SHORTCUT_NAME__", spec.shortcut_name)
    code = code.replace("__UNINSTALL_KEY__", spec.uninstall_key)
    code = code.replace("__PUBLISHER__", spec.publisher)
    code = code.replace("__FALLBACK_EXE__", spec.fallback_exe)
    cs_path.write_text(code, encoding="utf-8")


def build(spec: InstallerSpec | None = None):
    spec = spec or aipilot_spec()
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    csc = find_csc()
    version = get_version()
    print(f"[installer] Building {spec.output_exe.name} (v{version})...")
    print(f"[installer] Compiler: {csc}")

    ensure_launcher_image(spec)

    payload_zip = DIST_DIR / f".tmp_{spec.uninstall_key}_payload.zip"
    cs_source = DIST_DIR / f".tmp_{spec.uninstall_key}_installer.cs"

    try:
        make_payload_zip(spec, payload_zip)
        generate_installer_cs(spec, cs_source, version)

        cmd = [
            str(csc),
            "/target:winexe",
            "/platform:x64",
            "/optimize+",
            "/reference:System.dll",
            "/reference:System.Windows.Forms.dll",
            "/reference:System.Drawing.dll",
            "/reference:System.IO.Compression.dll",
            "/reference:System.IO.Compression.FileSystem.dll",
            f"/resource:{payload_zip},Payload",
            f"/out:{spec.output_exe}",
        ]
        if spec.icon_path.is_file():
            cmd.append(f"/win32icon:{spec.icon_path}")
        cmd.append(str(cs_source))

        print("[installer] Compiling installer...")
        res = subprocess.run(cmd, cwd=str(DIST_DIR), capture_output=True, text=True)
        if res.returncode != 0:
            print("[installer] Compilation errors:")
            print(res.stdout)
            print(res.stderr)
            raise RuntimeError(f"csc failed with code {res.returncode}")

        size_mb = spec.output_exe.stat().st_size / (1024 * 1024)
        h = hashlib.sha256(spec.output_exe.read_bytes()).hexdigest()
        sha_path = spec.output_exe.with_name(spec.output_exe.name + ".sha256")
        sha_path.write_text(f"{h}  {spec.output_exe.name}\n", encoding="utf-8")
        print(f"[installer] SUCCESS! Created {spec.output_exe} ({size_mb:.2f} MB, sha256: {h[:16]}...)")

    finally:
        if payload_zip.exists():
            payload_zip.unlink()
        if cs_source.exists():
            cs_source.unlink()


if __name__ == "__main__":
    build(parse_args())
