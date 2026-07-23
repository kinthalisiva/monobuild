package org.digitalforge.monobuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.collect.Streams;
import me.alexjs.dag.Dag;
import me.alexjs.dag.DagTraversalTask;
import org.digitalforge.monobuild.circleci.workflow.Job;
import org.digitalforge.monobuild.circleci.workflow.Workflow;
import org.digitalforge.monobuild.config.CircleCiConfig;
import org.digitalforge.monobuild.helper.*;
import org.eclipse.jgit.lib.Constants;

import org.digitalforge.monobuild.logging.console.Console;
import org.digitalforge.sneakythrow.SneakyThrow;

@Singleton
public class Monobuild {

    //TODO: make this part of monobuildConfig.json so you can configure the monobuild
    private final static String MAIN = "main"; //or 'master' for legacy githubs

    private final Boolean ci;
    private final Path outputDir;
    private final Path logDir;
    private final Path repoDir;
    private final Integer threadCount;
    private final String oldGitRef;
    private final Console console;
    private final ProjectTasks projectTasks;
    private final ProjectHelper projectHelper;
    private final RepoHelper repoHelper;
    private final ThreadHelper threadHelper;

    @Inject
    public Monobuild(
            @Named("ci") Boolean ci,
            @Named("outputDir") Path outputDir,
            @Named("logDir") Path logDir,
            @Named("repoDir") Path repoDir,
            @Named("threadCount") Integer threadCount,
            @Named("oldGitRef") String oldGitRef,
            Console console,
            ProjectTasks projectTasks,
            ProjectHelper projectHelper,
            RepoHelper repoHelper,
            ThreadHelper threadHelper
    ) {
        this.ci = ci;
        this.outputDir = outputDir;
        this.logDir = logDir;
        this.repoDir = repoDir;
        this.threadCount = threadCount;
        this.console = console;
        this.oldGitRef = oldGitRef;
        this.projectTasks = projectTasks;
        this.projectHelper = projectHelper;
        this.repoHelper = repoHelper;
        this.threadHelper = threadHelper;
    }

    public int buildTest(String[] args, String baseRef, List<String> includedProjects, List<String> excludedProjects) {

        if(baseRef == null) {
            baseRef = MAIN;
        }

        outputHeader();

        // Start the timer
        long start = System.currentTimeMillis();

        try {

            List<Project> allProjects = projectHelper.listAllProjects(repoDir);
            //TODO: modify main branch to be a configuration thing(monobuildConfig.json perhaps as yml sucks)
            Collection<String> changedFiles = repoHelper.diff(repoDir.toFile(), oldGitRef, Constants.HEAD, baseRef);
            List<Project> changedProjects = projectHelper.getChangedProjects(allProjects, changedFiles, repoDir);
            
            // Apply include/exclude filters to changed projects FIRST
            Stream<Project> filteredChangedStream = changedProjects.stream();
            
            if (includedProjects != null && !includedProjects.isEmpty()) {
                filteredChangedStream = filteredChangedStream.filter(project -> 
                    includedProjects.contains(project.name) || 
                    includedProjects.stream().anyMatch(include -> 
                        project.path.toString().contains(include)
                    )
                );
            }

            if (excludedProjects != null && !excludedProjects.isEmpty()) {
                filteredChangedStream = filteredChangedStream.filter(project -> 
                    !excludedProjects.contains(project.name) && 
                    excludedProjects.stream().noneMatch(exclude -> 
                        project.path.toString().contains(exclude)
                    )
                );
            }
            
            List<Project> filteredChangedProjects = filteredChangedStream.collect(Collectors.toList());
            
            Dag<Project> dag = projectHelper.getDependencyTree(allProjects, repoDir);

            // Build the affected projects, the projects that they depend on, and the projects that depend on them
            // Now using the FILTERED changed projects and properly filtering the dependency tree
            List<Project> projectsToBuild = filteredChangedProjects.stream()
                    .flatMap(p -> Streams.concat(
                        // Only include ancestors (dependencies) that match the include pattern
                        dag.getAncestors(p).stream()
                            .filter(ancestor -> includedProjects == null || includedProjects.isEmpty() || 
                                    includedProjects.stream().anyMatch(include -> 
                                        ancestor.path.toString().contains(include) || 
                                        ancestor.name.equals(include)
                                    )),
                        // Only include descendants (dependents) that match the include pattern
                        dag.getDescendants(p).stream()
                            .filter(descendant -> includedProjects == null || includedProjects.isEmpty() || 
                                    includedProjects.stream().anyMatch(include -> 
                                        descendant.path.toString().contains(include) || 
                                        descendant.name.equals(include)
                                    )),
                        Stream.of(p)
                    ))
                    .distinct()
                    // Final filter to ensure only included projects are built
                    .filter(project -> 
                        // Include if no includes specified, or if project matches include pattern
                        (includedProjects == null || includedProjects.isEmpty() || 
                         includedProjects.stream().anyMatch(include -> 
                             project.path.toString().contains(include) || 
                             project.name.equals(include)
                         )) &&
                        // Exclude if project matches exclude pattern
                        (excludedProjects == null || excludedProjects.isEmpty() || 
                         excludedProjects.stream().noneMatch(exclude -> 
                             project.path.toString().contains(exclude) || 
                             project.name.equals(exclude)
                         ))
                    )
                    .sorted(Comparator.comparing(p -> p.name))
                    .collect(Collectors.toList());

            StringJoiner changedJoiner = new StringJoiner("\n", "", "\n");
            StringJoiner builtJoiner = new StringJoiner("\n", "", "\n");

            console.header("Changed projects");
            if (!filteredChangedProjects.isEmpty()) {
                for (Project project : filteredChangedProjects) {
                    Path path = repoDir.relativize(project.path);
                    console.infoLeftRight(project.name, path);
                    changedJoiner.add(path.toString());
                }
            } else {
                console.info("No projects changed (after filtering)");
                return 0;
            }

            console.header("Affected projects");
            if (!projectsToBuild.isEmpty()) {
                for (Project project : projectsToBuild) {
                    Path path = repoDir.relativize(project.path);
                    console.infoLeftRight(project.name, path);
                    builtJoiner.add(path.toString());
                }
            } else {
                console.info("No projects to test");
                return 0;
            }

            // Write to files so we can see these lists after monobuild is complete
            writeProjectList("changed.txt", changedJoiner.toString());
            writeProjectList("built.txt", builtJoiner.toString());

            console.header("Building");

            ExecutorService buildThreadPool = threadHelper.newThreadPool("builder", threadCount);
            dag.retainAll(projectsToBuild);
            BiConsumer<Project, String[]> builder = (project, args2) -> {projectTasks.buildProject(project, args2);};
            DagTraversalTask<Project> buildTask = new DagTraversalTask<>(dag, new BiConsumerTask(args, builder), buildThreadPool);

            if (!buildTask.awaitTermination(2, TimeUnit.HOURS)) {
                console.error("Build failed: Timeout exceeded");
                return 1;
            }

            console.header("Testing");

            ExecutorService testThreadPool = threadHelper.newThreadPool("tester", threadCount);
            BiConsumer<Project, String[]> tester = (project, args2) -> {projectTasks.testProject(project, args2);};
            DagTraversalTask<Project> testTask = new DagTraversalTask<>(dag, new BiConsumerTask(args, tester), testThreadPool);

            if (!testTask.awaitTermination(2, TimeUnit.HOURS)) {
                console.error("Testing failed: Timeout exceeded");
                return 1;
            }

        } catch (InterruptedException | IOException e) {
            throw SneakyThrow.sneak(e);
        }

        // Stop the timer
        console.footer();
        console.infoLeftRight("Success! Total time", console.formatMillis(System.currentTimeMillis() - start));
        console.info("You can view all project build logs in " + logDir);

        return 0;

    }

    public int graph() {

        try {

            List<Project> allProjects = projectHelper.listAllProjects(repoDir);
            Dag<Project> dag = projectHelper.getDependencyTree(allProjects, repoDir);

            // Turn it into a more human-readable map, print it to the console, and save it as a json file

            console.infoLeftRight("Project", "Dependency");
            console.footer();

            Map<String, List<String>> outputMap = new TreeMap<>();
            for (Project project : dag.getNodes()) {

                List<String> dependencies = dag.getIncoming(project).stream()
                        .map(p -> p.name)
                        .collect(Collectors.toList());
                outputMap.put(project.name, dependencies);

                if (!dependencies.isEmpty()) {
                    for (String dependency : dependencies) {
                        console.infoLeftRight(project.name, dependency);
                    }
                } else {
                    console.infoLeftRight(project.name, "");
                }

            }

            // Write it to a file
            String json = JsonHelper.MAPPER.writeValueAsString(outputMap);
            writeFile("graph.json", json);

        } catch (IOException e) {
            throw SneakyThrow.sneak(e);
        }

        return 0;

    }

    public int circleciWorkflows(String baseRef) {

        if(baseRef == null) {
            baseRef = MAIN;
        }

        try {

            CircleCiConfig config = readCircleCiConfig();

            List<Project> allProjects = projectHelper.listAllProjects(repoDir);
            //TODO: modify main branch to be a configuration thing(monobuildConfig.json perhaps as yml sucks)
            Collection<String> changedFiles = repoHelper.diff(repoDir.toFile(), oldGitRef, Constants.HEAD, baseRef);
            List<Project> changedProjects = projectHelper.getChangedProjects(allProjects, changedFiles, repoDir);
            Dag<Project> dag = projectHelper.getDependencyTree(allProjects, repoDir);

            // Build the affected projects, the projects that they depend on, and the projects that depend on them
            List<Project> projectsToBuild = changedProjects.stream()
                    .flatMap(p -> Streams.concat(dag.getAncestors(p).stream(), dag.getDescendants(p).stream(), Stream.of(p)))
                    .distinct()
                    .sorted(Comparator.comparing(p -> p.name))
                    .collect(Collectors.toList());

            Map<String, Workflow> workflows = new TreeMap<>();
            Workflow buildWorkflow = new Workflow().setJobs(new ArrayList<>());

            buildWorkflow.getJobs().add("initialize");

            for (Project project : projectsToBuild) {

                Path relativePath = repoDir.relativize(project.path);

                Job projectJob = new Job();
                projectJob.setName("build-" + project.name);
                projectJob.setProjectdir(relativePath.toString());


                List<String> dependencies = dag.getIncoming(project).stream()
                        .map(p -> "build-" + p.name)
                        .collect(Collectors.toList());
                projectJob.setRequires(dependencies);

                if(projectJob.getRequires().isEmpty()) {
                    projectJob.getRequires().add("initialize");
                }

                projectJob.getRequires().sort(String::compareTo);

                String jobName = "build-" + relativePath.getName(0);

                if(jobName.equals("build-groovy") || jobName.equals("build-kotlin")) {
                    jobName = "build-java";
                }

                if(config.getJobs().containsKey(jobName)) {

                    CircleCiConfig.JobConfig jobConfig = config.getJobs().get(jobName);

                    List<String> configContext = jobConfig.getContext();

                    if(!configContext.isEmpty()) {
                        projectJob.setContext(configContext);
                    }

                }

                buildWorkflow.getJobs().add(Map.entry(jobName, projectJob));

            }

            if(projectsToBuild.isEmpty()) {
                buildWorkflow.getJobs().add("build-nothing");
            }
            else {
                buildWorkflow.getJobs().sort(Comparator.comparing(c -> (c instanceof String) ? c.toString() : ((Map.Entry<String, Job>)c).getValue().getName()));
            }

            workflows.put("build", buildWorkflow);

            Map<String, Map<String, Workflow>> root = new TreeMap<>();
            root.put("workflows", workflows);

            String yaml = YamlHelper.MAPPER.writeValueAsString(root);

            System.out.println(yaml);

            writeFile("circleci-workflows.yaml", yaml);

        } catch (IOException e) {
            throw SneakyThrow.sneak(e);
        }

        return 0;

    }

    public int deploy(String[] args, String baseRef) {

        if(baseRef == null) {
            baseRef = MAIN;
        }

        outputHeader();

        long start = System.currentTimeMillis();

        try {

            List<Project> allProjects = projectHelper.listAllProjects(repoDir);
            Collection<String> changedFiles = repoHelper.diff(repoDir.toFile(), oldGitRef, Constants.HEAD, baseRef);
            List<Project> changedProjects = projectHelper.getChangedProjects(allProjects, changedFiles, repoDir);
            Dag<Project> graph = projectHelper.getDependencyTree(allProjects, repoDir);

            // Build the affected projects, the projects that they depend on, and the projects that depend on them
            List<Project> projectsToBuild = changedProjects.stream()
                .flatMap(p -> Streams.concat(graph.getAncestors(p).stream(), graph.getDescendants(p).stream(), Stream.of(p)))
                .distinct()
                .sorted(Comparator.comparing(p -> p.name))
                .collect(Collectors.toList());

            List<Project> projectsToDeploy = projectsToBuild.stream()
                .filter(project -> Files.isExecutable(project.path.resolve("deploy.sh")))
                .collect(Collectors.toList());

            StringJoiner deployJoiner = new StringJoiner("\n", "", "\n");

            console.header("Pending Deployment");
            if (!projectsToDeploy.isEmpty()) {
                for (Project project : projectsToDeploy) {
                    Path path = repoDir.relativize(project.path);
                    console.infoLeftRight(project.name, path);
                    deployJoiner.add(path.toString());
                }
            } else {
                console.info("No projects to deploy");
                return 0;
            }

            writeProjectList("deployed.txt", deployJoiner.toString());

            console.header("Deploying");

            ExecutorService deploymentThreadPool = threadHelper.newThreadPool("deployment", threadCount);
            graph.retainAll(projectsToBuild);
            BiConsumer<Project, String[]> deployer = (project, args2) -> {projectTasks.testProject(project, args2);};
            DagTraversalTask<Project> deployTask = new DagTraversalTask<>(graph, new BiConsumerTask(args, deployer), deploymentThreadPool);

            if (!deployTask.awaitTermination(30, TimeUnit.MINUTES)) {
                console.error("Deployment failed");
                return 1;
            }

        } catch (InterruptedException | IOException e) {
            throw SneakyThrow.sneak(e);
        }

        // Stop the timer
        console.footer();
        console.infoLeftRight("Success! Total time", console.formatMillis(System.currentTimeMillis() - start));
        console.info("You can view all project deployment logs in " + logDir);

        return 0;

    }

    public int version() {
        outputHeader();
        return 0;
    }

    private void outputHeader() {

        String version = getClass().getPackage().getImplementationVersion();
        if(version == null) {
            version = "development";
        }

        // Print some logs
        console.infoLeftRight("Monobuild (" + version + ")", "https://github.com/DigitalForgeSoftworks/monobuild");
        console.infoLeftRight("CI", ci);
        console.infoLeftRight("Repo directory", repoDir);
        console.infoLeftRight("Log directory", logDir);
        console.infoLeftRight("Diff context", oldGitRef + ".." + Constants.HEAD);

    }

    private CircleCiConfig readCircleCiConfig() {
        return readConfigFile("circleci.json", CircleCiConfig.class);
    }

    private <T> T readConfigFile(String filename, Class<T> type) {

        try {

            Path file = repoDir.resolve(".monobuild").resolve(filename);

            if(!Files.isReadable(file)) {
                return null;
            }

            T content = JsonHelper.MAPPER.readValue(file.toFile(), type);

            return content;

        } catch(IOException ex) {
            throw SneakyThrow.sneak(ex);
        }

    }

    private void writeFile(String fileName, String text) {

        try {
            if(!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }
            Files.writeString(outputDir.resolve(fileName), text);
        } catch (IOException e) {
            throw SneakyThrow.sneak(e);
        }

    }

    private void writeProjectList(String fileName, String text) {
        try {
            Path pathsDir = outputDir.resolve("projects");
            if (!Files.exists(pathsDir)) {
                Files.createDirectories(pathsDir);
            }
            Files.writeString(pathsDir.resolve(fileName), text);
        } catch (IOException e) {
            throw SneakyThrow.sneak(e);
        }
    }

    private static class BiConsumerTask implements Consumer<Project> {

        private final String[] args;
        private final BiConsumer<Project, String[]> biconsumer;

        private BiConsumerTask(String[] args, BiConsumer biconsumer) {
            this.args = args;
            this.biconsumer = biconsumer;
        }

        @Override
        public void accept(Project project) {
            biconsumer.accept(project, args);
        }

    }

}
