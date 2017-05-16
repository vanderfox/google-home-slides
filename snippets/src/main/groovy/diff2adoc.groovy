File cd = new File('tour-de-plugin')
File demo = new File(cd, 'demo/tour-de-plugin-demo')
File outDir = new File(cd, 'snippets/src/docs/asciidoc/patches')
outDir.mkdirs()

// Capture command output (git commands in this case)
String executeAndCapture(String command, File currentDirectory) {
    def sout = new StringBuilder(), serr = new StringBuilder()

    def proc = command.execute(null, currentDirectory)

    proc.consumeProcessOutput(sout, serr)
    proc.waitForOrKill(1000)
    if (serr.toString()) {
        throw new RuntimeException("Error: ${serr.toString()}")

    }
    return sout.toString()
}

// Get project log
def log = executeAndCapture("git log --pretty=oneline --reverse", demo)

String LOG_PATTERN = /^([0-9a-z]+) (.*)$/
String FILE_TOUCH_PATTERN = /^:(?:(?:[0-9a-z]{6,7}[\s\.]*){4})\s([A-Z])[0-9]{0,3}\s+(.*)$/
String SOURCE_LINE_ADD = /\+(.+)$/
String SOURCE_LINE_REMOVE = /\-(.+)$/

String previousCommit = null
def changes = log.readLines().inject([]) { listOfChanges, String logLine ->

    def logMatch = logLine =~ LOG_PATTERN

    def sha = logMatch[0][1]
    def changeName = logMatch[0][2]

    if (!previousCommit) {
        previousCommit = sha
    } else {
        def change = [changeName: changeName]

        def filesTouched = executeAndCapture("git diff --raw ${previousCommit} ${sha}", demo)
        change.patches = filesTouched.readLines().collect {
            def fileTouchPatternMatch = it =~ FILE_TOUCH_PATTERN
            def fileStatus = fileTouchPatternMatch[0][1]
            def fileName = fileTouchPatternMatch[0][2]
            def patchDescription = [name: fileName, status: fileStatus]
            if (fileStatus != 'R') {
                def patch = executeAndCapture("git diff --patch ${previousCommit} ${sha} -- ${fileName}", demo)

                patchDescription.added = patch.readLines().inject([]) { listOfLines, line ->
                    def lineMatch = line =~ SOURCE_LINE_ADD
                    if (!line.startsWith('+++') && lineMatch.matches()) {
                        listOfLines << lineMatch[0][1]
                    }
                    return listOfLines
                }.join('\n').stripIndent().trim()
                patchDescription.removed = patch.readLines().inject([]) { listOfLines, line ->
                    def lineMatch = line =~ SOURCE_LINE_REMOVE
                    if (!line.startsWith('---') && lineMatch.matches()) {
                        listOfLines << lineMatch[0][1]
                    }
                    return listOfLines
                }.join('\n').stripIndent().trim()
            } else {
                def (from, to) = fileName.split(/\s/)
                patchDescription.name = from.trim()
                patchDescription.renamed = to.trim()
            }
            return patchDescription
        }
        previousCommit = sha
        listOfChanges << change
    }
    return listOfChanges
}


changes.eachWithIndex { change, index ->
    def file = new File(outDir, "${index + 1}_${change.changeName.toLowerCase().replaceAll(/\s/, '_')}.adoc")
    new PrintWriter(file).withPrintWriter { out ->
        out.println "=== Commit: ${change.changeName}\n"

        change.patches.each { patch ->
            out.println "==== `${patch.name}` \n"
            if (patch.status == 'D') {
                out.println "Delete file!\n"
                return
            }

            if (patch.status == 'R') {
                out.println "Rename to: `${patch.renamed}`"
                return
            }

            if (patch.removed) {
                out.println "[source.removed]"
                out.println ".${patch.name} (remove lines)"
                out.println "----"
                out.println "${patch.removed}"
                out.println "----\n"
            }

            if (patch.added) {
                out.println "[source.added]"
                if (patch.remove) {
                    out.println ".${patch.name}"
                }
                out.println "----"
                out.println "${patch.added.stripIndent()}"
                out.println "----\n"
            }
        }

    }
}
