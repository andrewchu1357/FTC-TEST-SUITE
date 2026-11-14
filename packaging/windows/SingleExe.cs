// FTC Test Suite in one file, for a download page (a GitHub release, say). This small launcher
// carries the Windows app folder (the windowsZip zip) inside it:
//
//   - The first start of each version unpacks the app into the user's own profile,
//     %LOCALAPPDATA%\FTC Test Suite\app\<version>\, with a small progress window. No installer,
//     no administrator, nothing outside the profile. Older versions there are removed.
//   - Every start then runs "FTC Test Suite.exe" from that folder with this program's arguments
//     (so --home "E:\My Robot" works as it does on the .exe in the zip). If Windows refuses that
//     .exe (Smart App Control on a PC that hasn't seen it yet), "Start FTC Test Suite.cmd" runs the
//     app on Java's own signed javaw.exe instead, as it does in the zip.
//   - --unpack-only unpacks and stops, without a window: errors go to the log and the exit code
//     (to prepare a PC in advance, or to test).
//   - Paths longer than Windows' old 260-character limit work (a long LOCALAPPDATA, say).
//
// Built by `gradlew windowsExe` (viewer/build.gradle) with the C# compiler that is part of Windows
// (.NET Framework 4), so nothing has to be installed to build or run it. That compiler knows
// C# 5 only: no newer syntax here. Build.cs, written by the task, holds the version.
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("FTC Test Suite")]
[assembly: AssemblyDescription("FTC Test Suite: the simulator for FTC robot code")]
[assembly: AssemblyProduct("FTC Test Suite")]
[assembly: AssemblyCompany("The FTC Test Suite Authors")]
[assembly: AssemblyCopyright("Apache License 2.0")]
// Today's .NET Framework behavior (4.8 is part of Windows 10 and 11), long paths included; without
// this the compiler's output runs in .NET 4.0's compatibility mode.
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8", FrameworkDisplayName = ".NET Framework 4.8")]

static class SingleExe
{
    const string AppName = "FTC Test Suite";
    const string Resource = "app.zip";
    const string Complete = ".complete";

    static bool quiet;

    [STAThread]
    static int Main(string[] args)
    {
        AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
        AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
        string dir = null;
        try
        {
            quiet = args.Length > 0 && args[0] == "--unpack-only";
            if (quiet) args = new string[0];
            string root = Path.Combine(Path.Combine(LocalAppData(), AppName), "app");
            dir = Path.Combine(root, Build.Folder);
            if (!File.Exists(Path.Combine(dir, Complete)))
            {
                if (!Unpack(root, dir)) return 1;
                RemoveOthers(root, Build.Folder);
            }
            if (!quiet) Start(dir, args);
            return 0;
        }
        catch (Exception e)
        {
            Fail(e, dir);
            return 1;
        }
    }

    /** A path Windows' file functions take at any length (the \\?\ form, for long ones). */
    static string Long(string path)
    {
        return path.Length < 240 || path.StartsWith(@"\\?\") ? path : @"\\?\" + path;
    }

    static string LocalAppData()
    {
        string env = Environment.GetEnvironmentVariable("LOCALAPPDATA");
        return string.IsNullOrEmpty(env) ? Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) : env;
    }

    /** Unpacks into a folder of its own, then renames it into place: a half-unpacked app is never used. */
    static bool Unpack(string root, string dir)
    {
        Directory.CreateDirectory(root);
        string partial = Path.Combine(root, "~" + Process.GetCurrentProcess().Id);
        Exception error = null;

        if (quiet)
        {
            try
            {
                Extract(partial, delegate(long done, long total) { });
                File.WriteAllText(Path.Combine(partial, Complete), Build.Version + "\r\n");
                MoveIntoPlace(partial, dir);
                return true;
            }
            catch (Exception e)
            {
                TryDelete(partial);
                Fail(e, dir);
                return false;
            }
        }

        Application.EnableVisualStyles();
        Form form = new Form();
        form.Text = AppName;
        form.FormBorderStyle = FormBorderStyle.FixedDialog;
        form.StartPosition = FormStartPosition.CenterScreen;
        form.ControlBox = false;
        form.ClientSize = new Size(440, 92);
        form.Font = SystemFonts.MessageBoxFont;
        try { form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }
        Label label = new Label();
        label.Text = "Getting " + AppName + " " + Build.Version + " ready. This happens only the first time.";
        label.SetBounds(16, 16, 408, 24);
        ProgressBar bar = new ProgressBar();
        bar.SetBounds(16, 48, 408, 20);
        bar.Maximum = 1000;
        form.Controls.Add(label);
        form.Controls.Add(bar);
        form.Shown += delegate
        {
            Thread worker = new Thread(delegate()
            {
                try
                {
                    Extract(partial, delegate(long done, long total)
                    {
                        int value = (int)(1000L * done / Math.Max(1L, total));
                        form.BeginInvoke((Action)delegate { bar.Value = Math.Min(1000, value); });
                    });
                    File.WriteAllText(Path.Combine(partial, Complete), Build.Version + "\r\n");
                    MoveIntoPlace(partial, dir);
                }
                catch (Exception e)
                {
                    error = e;
                }
                form.BeginInvoke((Action)form.Close);
            });
            worker.IsBackground = true;
            worker.Start();
        };
        Application.Run(form);

        if (error == null) return true;
        TryDelete(partial);
        Fail(error, dir);
        return false;
    }

    /** The zip in this program, without its top folder ("FTC Test Suite/"), into a new folder. */
    static void Extract(string into, Action<long, long> progress)
    {
        TryDelete(into);
        Directory.CreateDirectory(into);
        string full = Path.GetFullPath(into).TrimEnd('\\') + "\\";
        string top = AppName + "/";
        using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(Resource))
        using (ZipArchive zip = new ZipArchive(s, ZipArchiveMode.Read))
        {
            long total = 0, done = 0;
            foreach (ZipArchiveEntry e in zip.Entries) total += e.Length;
            foreach (ZipArchiveEntry e in zip.Entries)
            {
                string name = e.FullName.StartsWith(top) ? e.FullName.Substring(top.Length) : e.FullName;
                if (name.Length == 0) continue;
                string target = Path.GetFullPath(Path.Combine(into, name.Replace('/', '\\')));
                if (!target.StartsWith(full, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidDataException("The package holds a path outside its folder: " + e.FullName);
                }
                if (name.EndsWith("/"))
                {
                    Directory.CreateDirectory(Long(target));
                    continue;
                }
                Directory.CreateDirectory(Long(Path.GetDirectoryName(target)));
                e.ExtractToFile(Long(target), true);
                done += e.Length;
                progress(done, total);
            }
        }
    }

    /** Renames the unpacked folder into place. Another start may have got there first: then that one is used. */
    static void MoveIntoPlace(string partial, string dir)
    {
        if (Directory.Exists(dir))
        {
            if (File.Exists(Path.Combine(dir, Complete)))
            {
                TryDelete(partial);
                return;
            }
            Directory.Delete(Long(dir), true); // left half-made by an older launcher: replace it
        }
        Directory.Move(partial, dir);
    }

    /** Removes other versions and leftovers (best effort: a version that is running stays until next time). */
    static void RemoveOthers(string root, string keep)
    {
        foreach (string d in Directory.GetDirectories(root))
        {
            string name = Path.GetFileName(d);
            if (string.Equals(name, keep, StringComparison.OrdinalIgnoreCase)) continue;
            if (name.StartsWith("~") && Directory.GetLastWriteTimeUtc(d) > DateTime.UtcNow.AddHours(-1)) continue; // may be unpacking now
            try { File.Delete(Path.Combine(d, Complete)); } catch (Exception) { }
            TryDelete(d);
        }
    }

    static void Start(string dir, string[] args)
    {
        ProcessStartInfo app = new ProcessStartInfo(Path.Combine(dir, AppName + ".exe"), CommandLine(args));
        app.UseShellExecute = false;
        app.WorkingDirectory = dir;
        try
        {
            Process.Start(app);
        }
        catch (Win32Exception)
        {
            // Windows refused the .exe (Smart App Control, say): the app on Java's own signed javaw.exe.
            string cmd = Path.Combine(dir, "Start " + AppName + ".cmd");
            ProcessStartInfo java = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"),
                "/d /s /c \"\"" + cmd + "\" " + CommandLine(args) + "\"");
            java.UseShellExecute = false;
            java.CreateNoWindow = true;
            java.WorkingDirectory = dir;
            Process.Start(java);
        }
    }

    /** Arguments as one command line, quoted the way Windows programs read them back. */
    static string CommandLine(string[] args)
    {
        StringBuilder b = new StringBuilder();
        foreach (string a in args)
        {
            if (b.Length > 0) b.Append(' ');
            if (a.Length > 0 && a.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0)
            {
                b.Append(a);
                continue;
            }
            b.Append('"');
            int slashes = 0;
            foreach (char c in a)
            {
                if (c == '\\')
                {
                    slashes++;
                    continue;
                }
                if (c == '"')
                {
                    b.Append('\\', slashes * 2 + 1);
                }
                else
                {
                    b.Append('\\', slashes);
                }
                b.Append(c);
                slashes = 0;
            }
            b.Append('\\', slashes * 2);
            b.Append('"');
        }
        return b.ToString();
    }

    static void TryDelete(string dir)
    {
        try
        {
            if (Directory.Exists(dir)) Directory.Delete(Long(dir), true);
        }
        catch (Exception)
        {
            // in use or gone: next time
        }
    }

    static void Fail(Exception e, string dir)
    {
        string log = Path.Combine(Path.Combine(Path.Combine(LocalAppData(), AppName), "Logs"), AppName + ".log");
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(log));
            File.AppendAllText(log, "=== " + DateTime.Now + " " + AppName + " " + Build.Version + " (one-file launcher) couldn't start: " + e + "\r\n");
        }
        catch (Exception)
        {
            // no log then
        }
        if (quiet) return;
        MessageBox.Show(AppName + " couldn't start.\n\n" + e.Message
                + (dir == null ? "" : "\n\nIts folder: " + dir)
                + "\n\nThe details are in " + log
                + "\n\nThe zip download of the same version runs without this step.",
            AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
