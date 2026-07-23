package org.digitalforge.monobuild.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;

import org.digitalforge.monobuild.logging.console.Console;
import org.digitalforge.sneakythrow.SneakyThrow;

@Singleton
public class RepoHelper {

    private final Console console;

    @Inject
    public RepoHelper(Console console) {
        this.console = console;
    }

    public String getFileContents(File gitDir, String gitRef, String filePath) throws IOException {

        File file = RepositoryCache.FileKey.lenient(gitDir, FS.DETECTED).getFile();
        try (Repository repo = new RepositoryBuilder().setGitDir(file).build();
             Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             TreeWalk treeWalk = TreeWalk.forPath(git.getRepository(), filePath,
                     walk.parseCommit(repo.resolve(gitRef)).getTree())) {
            return new String(reader.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        } catch (NullPointerException exception) {
            return null;
        }

    }

    public String getMainBranchName(Path repoDir) {

        try {

            Process p = new ProcessBuilder()
                .command("git", "branch", "-r")
                .directory(repoDir.toFile())
                .start();

            try(BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String branch = br.lines()
                    .map(line -> line.trim())
                    .filter(line -> line.matches(".*/HEAD\\s+->.*"))
                    .map(line -> line.substring(line.lastIndexOf('>') + 1).trim())
                    .map(line -> line.substring(line.lastIndexOf('/') + 1))
                    .findFirst()
                    .orElse(null);

                return branch;

            }

        } catch(IOException ex) {
            throw SneakyThrow.sneak(ex);
        }


    }

    public Collection<String> diff(File repoDir, String oldRef, String newRef, String mainBranchName) {

        Set<String> allChangedFiles = new TreeSet<>();
        //BIG NOTE: We ONLY want to build projects where the developer has changed files.  We do NOT want to waste time
        //building projects from files changed on master as they are not yet on this branch anyways and that was causing
        //major delays in the build times.  This also happens locally to developers who fetch master all the time and it
        //starts building other projects really really confusing people since they had not changed those files.

        List<String> coll = runCommand(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
        console.infoLeftRight("Current branch", coll);//use whole list in case empty or more than 1
        String currentBranc = coll.get(0); //let it just fail with exception if not there
        //PLEASE READ post https://stackoverflow.com/questions/17493925/how-to-view-changed-files-on-git-branch-and-difference
        //In case we merged changes to main branch, we should know difference between last and current commit
        List<String> hashForkPointOfBranch;
        if (currentBranc.equals(mainBranchName)) {
            hashForkPointOfBranch = runCommand(repoDir, "git", "merge-base", currentBranc, "HEAD^1");
        } else {
            hashForkPointOfBranch = runCommand(repoDir, "git", "merge-base", currentBranc, mainBranchName);
        }
        console.infoLeftRight("Branched from Hash", hashForkPointOfBranch);//again, use whole list in case empty or more than 1
        String hash = hashForkPointOfBranch.get(0); //let it just fail with exception and we can debug

        List<String> filesCommitted = runCommand(repoDir, "git", "diff", "--name-only", hash, currentBranc);
        console.header("Files changed/comitted in branch");
        for(String s : filesCommitted) {
            console.info(s);
        }

        Collection<String> stagedChanges = runCommand(repoDir, "git", "diff", "--name-status", "--cached")
                .stream()
                .flatMap(new DiffSplitter())
                .collect(Collectors.toList());
        Collection<String> workingChanges = runCommand(repoDir, "git", "diff", "--name-status")
                .stream()
                .flatMap(new DiffSplitter())
                .collect(Collectors.toList());

        allChangedFiles.addAll(workingChanges);
        allChangedFiles.addAll(stagedChanges);

        console.header("Files changed & not yet committed");
        for(String s : allChangedFiles) {
            console.info(s);
        }

        allChangedFiles.addAll(filesCommitted);

        return allChangedFiles;
    }

    private List<String> runCommand(File directory, String... command) {

        try {

            Process p = new ProcessBuilder()
                .command(command)
                .directory(directory)
                .start();

            List<String> lines = new LinkedList<>();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }

            return lines;

        } catch(Exception ex) {
            throw SneakyThrow.sneak(ex);
        }

    }

    private class DiffSplitter implements Function<String, Stream<String>> {

        @Override
        public Stream<String> apply(String s) {
            String[] split = s.split("\t");
            if(split.length == 2) {
                return Stream.of(split[1]);
            } else {
                return Stream.of(split[1], split[2]);
            }
        }

    }

}
