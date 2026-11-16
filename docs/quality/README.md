# Quality and promotion controls

## Trust and bootstrap

The first trusted bootstrap on `main` must include `.github`, `.githooks`,
`scripts/quality`, `config/quality` and `gradle/quality.gradle`. Then create
`develop` and `release` from that commit and enable branch protection. Candidate
branches cannot supply their own scanner or rule configuration to authoritative
CI. `pull_request_target` loads the base policy; candidate source is treated as
data, and its Gradle build runs inside an unprivileged disposable container.

Changes to protected quality files require the exact protected-tree SHA-256 in
the repository variable `QUALITY_APPROVED_POLICY_HASH`, set after reviewing the
diff. That approval only accepts the precise content: scanners still execute the
trusted policy. A candidate cannot approve itself by editing a repository file.
After the policy reaches the trusted branch, remove the temporary approval hash.

Local hooks are useful feedback, not a security boundary. Configure them with
`git config core.hooksPath .githooks`. Install tools using:

```sh
python scripts/quality/install_tools.py --destination /absolute/task/cache/quality-tools
```

Add the directories listed in `paths.json` to PATH. On Windows, PMD and Trivy run
through Docker; PMD uses the verified distribution and JDK 25 in the build image.
The hook reads exact Git blobs from the index, including files marked
`export-ignore`; unstaged edits never enter its snapshot. It does not format or
rewrite the working tree. Missing tools, unsupported symlinks/submodules, scanner
failure or missing evidence fail closed.

## Every commit

`scan-range` enumerates all newly reachable commits, including intermediate and
merge commits. A new branch without verified baseline scans its entire reachable
history. A PR scans its complete head-versus-base range and a synthetic merged
tree. A divergent push uses the set difference, not only HEAD. CI does not cancel
earlier executions. Each SHA gets a separate evidence document with tree hash,
trusted policy hash and tool versions. No result cache is currently used.

Gitleaks always executes with full redaction. PMD analyzes production Java with
the actual compiled dependency classpath; the container cannot alter tracked
source before analysis. Application Checkov evidence covers Dockerfiles and
GitHub workflows. The independent [ticketing-infra repository](../../../ticketing-infra/README.md)
owns Terraform, TFLint and infrastructure Checkov gates; an application manifest
does not certify infrastructure merely because it references `infraGitSha`.
Trivy examines dependency artifacts and candidate images. An inapplicable
category is recorded explicitly. Generated scanner reports and caches stay
outside Git.

PMD 7.29.0, Gitleaks 8.30.1, Checkov 3.4.5 and Trivy 0.76.2 are pinned. The
installer verifies upstream release SHA-256 for downloaded CLI archives. Checkov
uses a dedicated virtual environment with its top-level version pinned; its full
resolved dependencies should also be retained in the build environment inventory.

## Sonar Community

The `quality` Compose profile and PostgreSQL are owned by application setup.
The default host endpoint is `http://127.0.0.1:19000`; set `SONAR_HOST_URL`
explicitly if the Compose mapping differs.
Initialize the server locally, replace its initial administrator credential, and
provide `SONAR_ADMIN_TOKEN` only while running `sonar_setup.py`. It configures
three private projects and the quality gate without printing credentials. Use
separate project-analysis tokens (`SONAR_TOKEN_CATALOG`, `SONAR_TOKEN_ORDERS`,
`SONAR_TOKEN_INVENTORY`) in environment `quality-develop` and restrict that
environment to `develop`. The dedicated self-hosted runner must have Python,
JDK 25, Docker and access to the local server; it receives no AWS deployment role.
Project-analysis tokens cannot read all private-project metrics. Supply a
separate `SONAR_READ_TOKEN` from a user with Browse permission on only these
three projects, and no global Execute Analysis/Create Projects permission.
The build receives neither analysis nor reader credentials; each scanner process
receives only its own project token. Compute-task polling uses that scoped project
token; analysis history, quality gates and metrics use the read-only token.

An exact trusted `develop` push in CI, or explicit local mode on a clean
`develop` checkout, updates `ticketing-catalog`, `ticketing-orders` and
`ticketing-inventory`. There are no `sonar.branch.name` or
PR parameters. Community is not presented as native multi-branch/PR analysis.
CI serializes the projects; do not run a local analysis concurrently with CI.
Evidence checks the compute task, revision and latest analysis before and after
reading project metrics to reject a competing candidate.

After merging to `develop`, committing all source/configuration changes and
starting the local quality profile, load only the three scoped analysis tokens,
the read-only `SONAR_READ_TOKEN` and `SONAR_HOST_URL` into the process environment.
Use JDK 25, keep AWS credentials absent, and run:

```sh
python scripts/quality/sonar_develop.py --local
```

Local mode rejects CI/PR environment markers; never fabricate `GITHUB_*` values.
It builds an isolated worktree at the actual HEAD and checks that branch, HEAD
and tracked/untracked source remain unchanged throughout. Reports are written
to `build/quality/sonar-local/<actual-SHA>/` with `executionContext: local`,
separately from CI's `build/quality/sonar/`. Local success is not a GitHub Actions
run or remote branch-protection evidence. Before worktree cleanup, exact JaCoCo,
JUnit and PIT XML files are retained under `reports/`; `report-manifest.json`
records their relative paths and SHA-256 hashes. A successful build must produce
the merged JaCoCo XML, JUnit XML and model/usecase PIT mutation XML. Failed runs
also retain available XML and sanitized compute-task/analysis identifiers for
diagnosis; this diagnostic manifest is never a successful gate result. Console
logs, task URLs and credentials are not copied.
The scanner's own JVM heap is capped
at 512 MiB; build processes never receive scanner credentials or generic JVM
options from the credential environment.

`sonar_develop.py` builds and tests, requires aggregate **unit** coverage >=90%
for lines and branches, runs the scanner with a 600-second gate wait, then checks
the specific compute task, analysis ID, SCM revision, gate and imported metrics.
Global coverage >=90%, duplication <=3%, ratings A, zero bugs/vulnerabilities and
reviewed hotspots are mandatory. New-code coverage is also checked when present.
Missing, malformed or non-finite metrics are rejected. A failed/offline Sonar server blocks
promotion; unit tests, PIT, concurrency and security scanners remain independent.

The pinned Community Build 26.11 supports Java 25, but does not bundle the
advanced Java symbolic-execution rules for issues such as null dereferences,
resource leaks and XXE. No optional advanced analyzer has been installed or
validated here. A green gate certifies the active installed rule set, not the
commercial security engine. See the official [Java support and advanced-rule
scope](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/languages/java#advanced-java-rules)
and [Gradle scanner requirements](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner-for-gradle).

## Repository configuration

Protect `develop`, `release` and `main` from direct pushes, deletion and force
push. Require the trusted `Static commits and branch policy` check and the
promotion check where applicable. Permit merge commits, preserve incremental
history, and disable squash/rebase for permanent branch promotions.

During release stabilization set `RELEASE_FROZEN=true`: only `fix/*` may merge
into develop. Fixes still follow develop -> release -> main. New features stay
on their work branches. Clear the freeze after the stable promotion.

Keep `ticketing-infra` separate. Cloud promotion needs its exact `infraGitSha`,
not an assumed local `infra/` directory. A manifest references the same three
image digests and content tree across branch merge commits. No workflow here
executes AWS apply or restores inventory snapshots for rollback.

After the complete successful `Quality` run on develop, freeze functional merges
and explicitly dispatch `Candidate` from develop. It selects the successful
exact-SHA Quality run automatically; dispatch parameters cannot change the build.
Set `APPROVED_INFRA_SHA` only after the separate infrastructure approval. The workflow checks run
provenance, unit XML, every required service's Sonar evidence, actual acceptance
reports, image vulnerability results and SBOMs. It publishes one manifest and
three immutable registry digests. QA/PROD do not rebuild these images. The
infrastructure pipeline must check `infraGitSha` against its own approved plan.

The application acceptance runner must write `build/quality/acceptance.json`
with exact `commit`, `tree`, and `checks` entries for `integration`, `architecture`,
`concurrency`, `recovery`, `containerSmoke`, **`expiry`**. The report must declare
`dirty: false`; development evidence with `--allow-dirty` cannot be promoted.
The expiry check waits ten real minutes with HTTP lazy expiry and periodic
recovery disabled, checks authoritative persisted state and observes actual SQS
consumer processing. Unit tests with a controlled clock cannot replace it.
Every entry needs `status: passed`
and an `evidence` path to a nonempty report within the repository. Missing tests
or evidence block publication instead of producing an optimistic green manifest.

The separate [Postman/Newman runner](../../scripts/postman/README.md) executes the
user-facing collection and records a redacted local report. It complements the
acceptance suite; it does not replace the mandatory expiry, concurrency,
architecture, recovery, Sonar or security gates. A collection import or runner
unit test is not evidence that its HTTP requests succeeded.

Each develop -> release and release -> main PR includes a body line
`Candidate-Run: <successful Candidate workflow run ID>`. The trusted promotion
job retrieves that exact run's artifact, checks workflow/repository/branch/event
provenance, verifies all image digests and Sonar evidence, and requires source and
merge content to match. The source SHA must be an ancestor of the promoted head;
develop promotion additionally requires it to be the current head itself.

## Verification status

The Python tests exercise Git snapshot fidelity, multi-commit/merge/divergent
histories, rule-change approval, branch freezes, coverage rejection, stale Sonar
evidence and immutable image manifest checks. Running these tests does not mean
PMD, Gitleaks, Checkov, Trivy, Sonar, Docker or AWS have executed on the application.
Actual results must be retained under the candidate's own commit and tree hash.
