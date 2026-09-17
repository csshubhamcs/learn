rootProject.name = "learn"

// Every service in the monorepo is listed here, and that has two consequences worth knowing
// before you add the next one.
//
// 1 · Gradle refuses to configure the build unless EVERY directory named below exists. A
//     Dockerfile that copied only its own service and then ran Gradle inside the image failed
//     with "Configuring project ':task-service' without an existing directory is not allowed".
//     That is why the Dockerfiles package a jar Gradle has already built rather than
//     compiling in the image.
//
// 2 · Adding a module here is not enough to get it deployed. It also needs an entry in the
//     paths filter in .github/workflows/build-and-publish.yml, a chart in learn-helm-chart,
//     and an environments/<env>/<service>.yaml. Miss the paths filter and pushes to that
//     service match nothing: the matrix is empty, every job is skipped, and the workflow
//     reports SUCCESS while publishing nothing at all.
include("user-service")
include("task-service")
