# Getting Started

### AV scanner

To create a docker image for av scanner you can use the following command:

```bash
docker build -f Dockerfile-scanner -t file-manager/scanner-simulator:latest .  
```

Now that you have the image, you can the application from your IDE. Just run the `TestFileManagerApplication` class as a Java
application.


### StreamingArchiveService

This branch contains the `StreamingArchiveService` which is a hybrid reactive model that uses a blocking Java I/O library (`ZipOutputStream`) and integrates it into a reactive stream by offloading the blocking work to a dedicated thread pool (`Schedulers.boundedElastic`).
To learn more about the architecture and performance comparison, refer to the [comparison documentation](architecture/comparison%20%28performance%29.md).
