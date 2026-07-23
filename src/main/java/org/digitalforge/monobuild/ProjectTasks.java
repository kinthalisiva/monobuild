package org.digitalforge.monobuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.pty4j.PtyProcessBuilder;

import org.digitalforge.monobuild.helper.StreamHelper;
import org.digitalforge.monobuild.logging.console.Console;
import org.digitalforge.sneakythrow.SneakyThrow;

@Singleton
public class ProjectTasks {

    private final Path logDir;
    private final Path repoDir;
    private final Console console;
    private final StreamHelper streamHelper;

    @Inject
    public ProjectTasks(@Named("logDir") Path logDir,
                        @Named("repoDir") Path repoDir,
                        Console console,
                        StreamHelper streamHelper) {
        this.logDir = logDir;
        this.repoDir = repoDir;
        this.console = console;
        this.streamHelper = streamHelper;
    }

    public void buildProject(Project project, String[] args) {

        List<String> cmd = new ArrayList<>(3 + args.length);
        cmd.addAll(List.of("sh", "-c", "./build.sh"));
        cmd.addAll(List.of(args));

        timedSafeExecute(project, start -> {

            console.infoLeftRight("Starting to build", project.name);

            // Use JetBrains' PtyProcessBuilder to capture colored output
            PtyProcessBuilder processBuilder = new PtyProcessBuilder()
                .setCommand(cmd.toArray(new String[cmd.size()]))
                .setDirectory(project.path.toString())
                .setRedirectErrorStream(true);
            processBuilder.setEnvironment(new HashMap<>(System.getenv()));

            Process process = processBuilder.start();

            // Stream the output to a log file and return a reference to the OutputStream
            Path logFile = logDir.resolve(project.name + ".build.log");
            CompletableFuture<String> output = streamHelper.forkToFileAndString(process.getInputStream(), logFile);

            if(process.waitFor() != 0) {
                System.out.println(output.get());
                System.out.flush();
                long elapsed = System.currentTimeMillis() - start;
                console.errorLeftRight("Failed to build (%s)", console.formatMillis(elapsed), project.name);
                System.exit(1);
            }
            else {
                long elapsed = System.currentTimeMillis() - start;
                console.infoLeftRight("Finished building (%s)", console.formatMillis(elapsed), project.name);
            }

        });

    }

    public void deployProject(Project project, String[] args) {

        List<String> cmd = new ArrayList<>(3 + args.length);
        cmd.addAll(List.of("sh", "-c", "./deploy.sh"));
        cmd.addAll(List.of(args));

        Path deployScript = project.path.resolve("deploy.sh");

        if(!Files.exists(deployScript) || !Files.isExecutable(deployScript)) {
            return;
        }

        timedSafeExecute(project, start -> {

            // Use JetBrains' PtyProcessBuilder to capture colored output
            PtyProcessBuilder processBuilder = new PtyProcessBuilder()
                .setCommand(cmd.toArray(new String[cmd.size()]))
                .setDirectory(project.path.toString())
                .setRedirectErrorStream(true);
            processBuilder.setEnvironment(new HashMap<>(System.getenv()));

            Process process = processBuilder.start();

            // Stream the output to a log file and return a reference to the OutputStream
            Path logFile = logDir.resolve(project.name + ".deploy.log");
            CompletableFuture<String> output = streamHelper.forkToFileAndString(process.getInputStream(), logFile);

            if(process.waitFor() != 0) {
                System.out.println(output.get());
                System.out.flush();
                long elapsed = System.currentTimeMillis() - start;
                console.errorLeftRight("Failed to deploy (%s)", console.formatMillis(elapsed), project.name);
                System.exit(1);
            }
            else {
                long elapsed = System.currentTimeMillis() - start;
                console.infoLeftRight("Finished deploying (%s)", console.formatMillis(elapsed), project.name);
            }

        });

    }

    public void testProject(Project project, String[] args) {

        List<String> cmd = new ArrayList<>(3 + args.length);
        cmd.addAll(List.of("sh", "-c", "./test.sh"));
        cmd.addAll(List.of(args));

        timedSafeExecute(project, start -> {

            // Use JetBrains' PtyProcessBuilder to capture colored output
            PtyProcessBuilder processBuilder = new PtyProcessBuilder()
                .setCommand(cmd.toArray(new String[cmd.size()]))
                .setDirectory(project.path.toString())
                .setRedirectErrorStream(true);
            processBuilder.setEnvironment(new HashMap<>(System.getenv()));

            Process process = processBuilder.start();

            // Stream the output to a log file and return a reference to the OutputStream
            Path logFile = logDir.resolve(project.name + ".test.log");
            CompletableFuture<String> output = streamHelper.forkToFileAndString(process.getInputStream(), logFile);

            if(process.waitFor() != 0) {
                System.out.println(output.get());
                System.out.flush();
                long elapsed = System.currentTimeMillis() - start;
                console.errorLeftRight("Failed to test (%s)", console.formatMillis(elapsed), project.name);
                System.exit(1);
            }
            else {
                long elapsed = System.currentTimeMillis() - start;
                console.infoLeftRight("Finished testing (%s)", console.formatMillis(elapsed), project.name);
            }

        });

    }

    private void timedSafeExecute(Project project, TimedTask<Long> timedTask) {

        long start = System.currentTimeMillis();

        try {
            timedTask.accept(start);
        } catch (InterruptedException e) {
            long elapsed = System.currentTimeMillis() - start;
            console.errorLeftRight("Interrupted while executing (%s)", console.formatMillis(elapsed), repoDir.relativize(project.path));
        } catch (IOException | ExecutionException e) {
            long elapsed = System.currentTimeMillis() - start;
            console.errorLeftRight("Exception while executing (%s)", console.formatMillis(elapsed), repoDir.relativize(project.path));
            e.printStackTrace();
            throw SneakyThrow.sneak(e);
        }

    }

    @FunctionalInterface
    private interface TimedTask<T> {

        void accept(T value) throws IOException, InterruptedException, ExecutionException;

    }

}
