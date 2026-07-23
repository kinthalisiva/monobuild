package org.digitalforge.monobuild.command;

import java.util.List;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import javax.inject.Singleton;

import picocli.CommandLine;

import org.digitalforge.monobuild.Monobuild;

@Singleton
@CommandLine.Command(name = "monobuild", description = "Run monobuild")
public class MonobuildCommand implements Callable<Integer> {

    private final Monobuild monobuild;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help and exit")
    private boolean help;

    @CommandLine.ArgGroup(exclusive = true)
    private BuildOptions buildOptions;

    @CommandLine.Parameters
    private List<String> parameters;

    @Inject
    public MonobuildCommand(Monobuild monobuild) {
        this.monobuild = monobuild;
    }

    @Override
    public Integer call() {
        if(parameters == null) {
            parameters = List.of();
        }

        String baseRef = null;
        List<String> includedProjects = null;
        List<String> excludedProjects = null;
        
        if(buildOptions != null) {
            baseRef = buildOptions.baseTag;
            if(baseRef == null) {
                baseRef = buildOptions.baseBranch;
            }
            includedProjects = buildOptions.includedProjects;
            excludedProjects = buildOptions.excludedProjects;
        }

        return monobuild.buildTest(parameters.toArray(new String[0]), baseRef, includedProjects, excludedProjects);
    }

    @CommandLine.Command(name = "graph", description = "Find and print the graph of the monorepo")
    public Integer graph(@CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help and exit") boolean help) {
        return monobuild.graph();
    }

    @CommandLine.Command(name = "circleci-workflows", description = "Print a CircleCI config Workflows section for changed projects")
    public Integer circleciWorkflows(@CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help and exit") boolean help) {

        String baseRef = null;
        if(buildOptions != null) {
            baseRef = buildOptions.baseTag;
            if(baseRef == null) {
                baseRef = buildOptions.baseBranch;
            }
        }

        return monobuild.circleciWorkflows(baseRef);

    }

    @CommandLine.Command(name = "deploy", description = "Deploy changed projects in the monorepo")
    public Integer deploy(@CommandLine.Parameters String[] parameters) {
        if(parameters == null) {
            parameters = new String[0];
        }
        String baseRef = null;
        if(buildOptions != null) {
            baseRef = buildOptions.baseTag;
            if(baseRef == null) {
                baseRef = buildOptions.baseBranch;
            }
        }
        return monobuild.deploy(parameters, baseRef);
    }

    @CommandLine.Command(name = "version", description = "Show version & configuration")
    public Integer version() {
        return monobuild.version();
    }

    static class BuildOptions {

        @CommandLine.Option(names = {"-t", "--tag"}, description = "Base tag to compare against")
        String baseTag;

        @CommandLine.Option(names = {"-b", "--branch"}, description = "Base branch to compare against")
        String baseBranch;

        @CommandLine.Option(names = {"-i", "--include"}, split = ",", description = "Comma-separated list of projects to include")
        List<String> includedProjects;

        @CommandLine.Option(names = {"-e", "--exclude"}, split = ",", description = "Comma-separated list of projects to exclude")
        List<String> excludedProjects;

    }

}
