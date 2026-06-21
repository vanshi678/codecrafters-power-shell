
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    // -----------------------------------------------------------------------
    // Builtin registry
    // -----------------------------------------------------------------------

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo")
                || cmd.equals("type")
                || cmd.equals("pwd")
                || cmd.equals("cd")
                || cmd.equals("jobs")
                || cmd.equals("exit");
    }

    /**
     * Execute a built-in command and return its stdout as a String.
     * Commands that need the current directory (pwd) receive it via the
     * {@code currentDirectory} parameter.
     * Commands that are not "output-producing" (cd, exit, jobs) return "".
     */
    private static String runBuiltin(List<String> cmd, File currentDirectory) {

        String command = cmd.get(0);

        switch (command) {

            case "echo": {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < cmd.size(); i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(cmd.get(i));
                }
                sb.append("\n");
                return sb.toString();
            }

            case "pwd": {
                return currentDirectory.getAbsolutePath() + "\n";
            }

            case "type": {
                if (cmd.size() < 2) return "";
                String target = cmd.get(1);
                if (isBuiltin(target)) {
                    return target + " is a shell builtin\n";
                }
                // search PATH
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null) {
                    for (String dir : pathEnv.split(File.pathSeparator)) {
                        File f = new File(dir, target);
                        if (f.exists() && f.isFile() && f.canExecute()) {
                            return target + " is " + f.getAbsolutePath() + "\n";
                        }
                    }
                }
                return target + ": not found\n";
            }

            default:
                return "";
        }
    }

    // -----------------------------------------------------------------------
    // Background job tracking
    // -----------------------------------------------------------------------

    static class Job {
        int jobId;
        Process process;
        String command;

        Job(int jobId, Process process, String command) {
            this.jobId = jobId;
            this.process = process;
            this.command = command;
        }
    }

    private static int getNextJobId(List<Job> jobs) {
        int maxJobId = 0;
        for (Job job : jobs) maxJobId = Math.max(maxJobId, job.jobId);
        return maxJobId + 1;
    }

    private static void reapJobs(List<Job> jobs) {

        int lastJobId = -1, secondLastJobId = -1;
        for (Job job : jobs) {
            if (job.jobId > lastJobId) { secondLastJobId = lastJobId; lastJobId = job.jobId; }
            else if (job.jobId > secondLastJobId) secondLastJobId = job.jobId;
        }

        List<Job> done = new ArrayList<>();
        for (Job job : jobs) {
            boolean running;
            try { job.process.exitValue(); running = false; }
            catch (IllegalThreadStateException e) { running = true; }

            if (!running) {
                char marker = job.jobId == lastJobId ? '+' : (job.jobId == secondLastJobId ? '-' : ' ');
                // Always strip trailing & from command display
                String cmd = job.command.trim();
                if (cmd.endsWith("&")) cmd = cmd.substring(0, cmd.length() - 1).trim();
                System.out.println("[" + job.jobId + "]" + marker + "  " + String.format("%-24s", "Done") + cmd);
                done.add(job);
            }
        }
        jobs.removeAll(done);
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    /**
     * Split a raw input line on unquoted pipe characters ('|').
     * Handles single quotes, double quotes, and backslash escapes so that
     * pipes inside strings are NOT treated as pipeline separators.
     */
    private static List<String> splitOnPipes(String input) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (ch == '\\' && !inSingle) {
                // consume escape + next char verbatim
                current.append(ch);
                if (i + 1 < input.length()) current.append(input.charAt(++i));
            } else if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(ch);
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(ch);
            } else if (ch == '|' && !inSingle && !inDouble) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        segments.add(current.toString());
        return segments;
    }

    /**
     * Parse a single command string into a list of tokens, respecting
     * single-quotes, double-quotes, and backslash escapes.
     */
    private static List<String> parseCommand(String input) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (ch == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) { current.append(input.charAt(++i)); }
                else { current.append('\\'); }
            } else if (ch == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') { current.append(next); i++; }
                    else { current.append('\\'); }
                } else { current.append('\\'); }
            } else if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) { parts.add(current.toString()); current.setLength(0); }
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    // -----------------------------------------------------------------------
    // Pipeline execution
    // -----------------------------------------------------------------------

    /**
     * Execute a pipeline of N segments.
     *
     * Strategy:
     *  - For each segment decide: is it a builtin or an external?
     *  - Chain them left-to-right using Java threads + streams:
     *      builtin  -> produces a byte[] that is fed into the next stage
     *      external -> spawned as a Process; its stdin is wired to the
     *                  previous stage's output via a pump thread
     *  - The final stage's stdout goes to System.out.
     */
    private static void executePipeline(List<String> segments, File currentDirectory) throws Exception {

        int n = segments.size();

        // Parse each segment into tokens
        List<List<String>> cmds = new ArrayList<>();
        for (String seg : segments) cmds.add(parseCommand(seg.trim()));

        // Validate
        for (List<String> cmd : cmds) {
            if (cmd.isEmpty()) return; // malformed pipeline
        }

        // We maintain a "current input" as a byte[] for builtin→builtin or
        // builtin→external handoff.  For external→X we use Process streams.

        // Run stages left to right, keeping track of the previous stage
        // so we can wire stdout→stdin correctly.

        // Representation of the output of a completed/running stage:
        //   Either a Process (external) or a byte[] (builtin output ready)
        Process prevProcess = null;       // last external process (or null)
        byte[] prevBuiltinOut = null;     // last builtin's stdout bytes (or null)
        // Exactly one of these is non-null at any point (or both null initially).

        for (int i = 0; i < n; i++) {
            List<String> cmd = cmds.get(i);
            boolean isLast = (i == n - 1);
            boolean builtin = isBuiltin(cmd.get(0));

            if (builtin) {
                // ---- Collect input for this builtin (if any) ----
                String stdinForBuiltin = null;
                if (prevProcess != null) {
                    // Drain previous process stdout as our stdin
                    byte[] data = prevProcess.getInputStream().readAllBytes();
                    prevProcess.waitFor();
                    prevBuiltinOut = prevProcess.getInputStream().readAllBytes();
                    prevProcess.waitFor();
                    prevProcess = null;
                }
                String out = runBuiltin(cmd, currentDirectory);
                prevBuiltinOut = out.getBytes();
                if (isLast) System.out.print(out);
            } else {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(currentDirectory);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                if (isLast) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                Process proc = pb.start();

                if (prevBuiltinOut != null) {
                    final byte[] data = prevBuiltinOut;
                    prevBuiltinOut = null;
                    Thread t = new Thread(() -> {
                        try (OutputStream os = proc.getOutputStream()) { os.write(data); }
                        catch (Exception ignored) {}
                    });
                    t.setDaemon(true);
                    t.start();
                } else if (prevProcess != null) {
                    final Process src = prevProcess;
                    Thread t = new Thread(() -> {
                        try (InputStream in = src.getInputStream();
                             OutputStream out = proc.getOutputStream()) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) != -1) { out.write(buf, 0, len); out.flush(); }
                        } catch (Exception ignored) {}
                    });
                    t.setDaemon(true);
                    t.start();
                } else {
                    proc.getOutputStream().close();
                }

                if (isLast) {
                    proc.waitFor();
                } else {
                    // Non-last external: hand off to next stage
                    prevProcess = proc;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));
        List<Job> jobs = new ArrayList<>();

        while (true) {

            Thread.sleep(50);
            reapJobs(jobs);

            System.out.print("$ ");
            System.out.flush();
            String input = sc.nextLine();

            // Pipeline detection (unquoted '|')
            List<String> pipeSegments = splitOnPipes(input);

            if (pipeSegments.size() > 1) {
                executePipeline(pipeSegments, currentDirectory);
                continue;
            }
            // Single command — parse tokens
            List<String> parsed = parseCommand(input);
            if (parsed.isEmpty()) continue;

            String cmd = parsed.get(0);
            String[] parts = parsed.toArray(new String[0]);

            // ---- Redirect parsing ----
            String outputFile = null, errorFile = null;
            boolean appendOutput = false, appendError = false;
            List<String> commandParts = new ArrayList<>();

            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals(">") || parts[i].equals("1>")) {
                    if (i + 1 < parts.length) { outputFile = parts[++i]; appendOutput = false; }
                } else if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                    if (i + 1 < parts.length) { outputFile = parts[++i]; appendOutput = true; }
                } else if (parts[i].equals("2>")) {
                    if (i + 1 < parts.length) { errorFile = parts[++i]; appendError = false; }
                } else if (parts[i].equals("2>>")) {
                    if (i + 1 < parts.length) { errorFile = parts[++i]; appendError = true; }
                } else {
                    commandParts.add(parts[i]);
                }
            }
            parts = commandParts.toArray(new String[0]);

            // ---- Background job flag ----
            boolean background = false;
            if (parts.length > 0 && parts[parts.length - 1].equals("&")) {
                background = true;
                String[] newParts = new String[parts.length - 1];
                System.arraycopy(parts, 0, newParts, 0, parts.length - 1);
                parts = newParts;
            }

            if (parts.length == 0) continue;
            cmd = parts[0];

            // Built-in commands

            // exit
            if (cmd.equals("exit")) break;

            // echo
            if (cmd.equals("echo")) {
                StringBuilder output = new StringBuilder();
                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) output.append(" ");
                    output.append(parts[i]);
                }
                String line = output.toString();
                if (outputFile != null) {
                    byte[] bytes = (line + System.lineSeparator()).getBytes();
                    if (appendOutput)
                        Files.write(Paths.get(outputFile), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    else
                        Files.write(Paths.get(outputFile), bytes);
                } else {
                    System.out.println(line);
                }
                if (errorFile != null) {
                    if (appendError)
                        Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    else
                        Files.write(Paths.get(errorFile), new byte[0]);
                }
                continue;
            }

            // pwd
            if (cmd.equals("pwd")) {
                String result = currentDirectory.getAbsolutePath();
                if (outputFile != null) {
                    byte[] bytes = (result + System.lineSeparator()).getBytes();
                    if (appendOutput)
                        Files.write(Paths.get(outputFile), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    else
                        Files.write(Paths.get(outputFile), bytes);
                } else {
                    System.out.println(result);
                }
                if (errorFile != null) Files.write(Paths.get(errorFile), new byte[0]);
                continue;
            }

            // cd
            if (cmd.equals("cd")) {
                if (parts.length < 2) continue;
                String path = parts[1];
                File newDirectory;
                if (path.equals("~")) {
                    String home = System.getenv("HOME");
                    if (home == null) home = System.getProperty("user.home");
                    newDirectory = new File(home);
                } else if (new File(path).isAbsolute()) {
                    newDirectory = new File(path);
                } else {
                    newDirectory = new File(currentDirectory, path);
                }
                newDirectory = newDirectory.getCanonicalFile();
                if (newDirectory.exists() && newDirectory.isDirectory()) {
                    currentDirectory = newDirectory;
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
                continue;
            }

            // jobs
            if (cmd.equals("jobs")) {
                int lastJobId = -1, secondLastJobId = -1;
                for (Job job : jobs) {
                    if (job.jobId > lastJobId) { secondLastJobId = lastJobId; lastJobId = job.jobId; }
                    else if (job.jobId > secondLastJobId) secondLastJobId = job.jobId;
                }
                List<Job> completedJobs = new ArrayList<>();
                for (Job job : jobs) {
                    char marker = job.jobId == lastJobId ? '+' : (job.jobId == secondLastJobId ? '-' : ' ');
                    boolean running;
                    try { job.process.exitValue(); running = false; }
                    catch (IllegalThreadStateException e) { running = true; }
                    String status = running ? String.format("%-24s", "Running") : String.format("%-24s", "Done");
                    // Always strip trailing & from the command display
                    String jobCmd = job.command.trim();
                    if (jobCmd.endsWith("&")) jobCmd = jobCmd.substring(0, jobCmd.length() - 1).trim();
                    if (!running) {
                        completedJobs.add(job);
                    }
                    System.out.println("[" + job.jobId + "]" + marker + "  " + status + jobCmd);
                }
                jobs.removeAll(completedJobs);
                continue;
            }

            // type
            if (cmd.equals("type")) {
                if (parts.length < 2) continue;
                String target = parts[1];
                String result;
                if (isBuiltin(target)) {
                    result = target + " is a shell builtin";
                } else {
                    result = null;
                    String pathEnv2 = System.getenv("PATH");
                    if (pathEnv2 != null) {
                        for (String dir : pathEnv2.split(File.pathSeparator)) {
                            File f = new File(dir, target);
                            if (f.exists() && f.isFile() && f.canExecute()) {
                                result = target + " is " + f.getAbsolutePath();
                                break;
                            }
                        }
                    }
                }
                if (result != null) {
                    if (outputFile != null) {
                        byte[] bytes = (result + System.lineSeparator()).getBytes();
                        if (appendOutput)
                            Files.write(Paths.get(outputFile), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        else
                            Files.write(Paths.get(outputFile), bytes);
                    } else {
                        System.out.println(result);
                    }
                } else {
                    System.out.println(target + ": not found");
                }
                if (errorFile != null) Files.write(Paths.get(errorFile), new byte[0]);
                continue;
            }

            // External executabl
            String pathEnv = System.getenv("PATH");
            File executable = null;
            if (pathEnv != null) {
                for (String dir : pathEnv.split(File.pathSeparator)) {
                    File f = new File(dir, cmd);
                    if (f.exists() && f.isFile() && f.canExecute()) { executable = f; break; }
                }
            }

            if (executable != null) {
                List<String> command = new ArrayList<>();
                for (String part : parts) command.add(part);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(currentDirectory);

                if (outputFile != null) {
                    if (appendOutput) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                    else pb.redirectOutput(new File(outputFile));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                if (errorFile != null) {
                    if (appendError) pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorFile)));
                    else pb.redirectError(new File(errorFile));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();

                if (background) {
                    int jobId = getNextJobId(jobs);
                    jobs.add(new Job(jobId, process, input));
                    System.out.println("[" + jobId + "] " + process.pid());
                } else {
                    process.waitFor();
                }

            } else {
                String msg = cmd + ": command not found";
                if (errorFile != null) {
                    byte[] bytes = (msg + System.lineSeparator()).getBytes();
                    if (appendError)
                        Files.write(Paths.get(errorFile), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    else
                        Files.write(Paths.get(errorFile), bytes);
                } else {
                    System.out.println(msg);
                }
            }
        }

        sc.close();
    }
}
