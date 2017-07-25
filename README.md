[![Build Status](https://travis-ci.org/LREN-CHUV/woken.svg?branch=master)](https://travis-ci.org/LREN-CHUV/woken)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/3a1e546e9f124b44a829f2d0ea488a96)](https://www.codacy.com/app/hbp-mip/woken?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=LREN-CHUV/woken&amp;utm_campaign=Badge_Grade) [![Codacy Badge](https://api.codacy.com/project/badge/Coverage/3a1e546e9f124b44a829f2d0ea488a96)](https://www.codacy.com/app/hbp-mip/woken?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=LREN-CHUV/woken&amp;utm_campaign=Badge_Coverage) [![Dependencies](https://app.updateimpact.com/badge/776816605463187456/woken.svg?config=compile)](https://app.updateimpact.com/latest/776816605463187456/woken)
<!-- TODO
[![codecov.io](https://codecov.io/github/LREN-CHUV/woken/coverage.svg?branch=master)](https://codecov.io/github/LREN-CHUV/woken?branch=master)
-->

# woken: Workflow for Analytics

An orchestration platform for Docker containers running data mining algorithms.

This project exposes a web interface to execute on demand data mining algorithms defined in Docker containers and implemented using any tool or language (R, Python, Java and more are supported).

It relies on a runtime environment containing [Mesos](http://mesos.apache.org) and [Chronos])(https://mesos.github.io/chronos/) to control and execute the Docker containers over a cluster.

## Prerequisite

Woken requires [Captain](https://github.com/harbur/captain) and [docker-compose](https://docs.docker.com/compose/) to be built and executed.

## Getting started

Follow these steps to get started:

Follow these steps to get started:

1. Git-clone this repository.

        $ git clone https://github.com/LREN-CHUV/woken.git

2. Change directory into your clone:

        $ cd woken

3. Clone submodules

        $ git submodule update --init --recursive

4. Create and add the interfaces routes to the host filters

        $ docker network create woken-bridge
        $ docker network inspect woken-bridge | grep 'Gateway' | cut -d: -f2 | awk '{ print $1}' | tr -d '"'

        paste the IP address

        $ sudo nano /etc/hosts

        and add

        xxx.xxx.xxx.xxx      woken
        xxx.xxx.xxx.xxx      woken-validation

        where xxx.xxx.xxx.xxx is your the ip address of the woken-bridge interface

        > nano ~/.bashrc

        add the line export HOST=xxx.xxx.xxx.xxx

## To use the test environment

5. Build the application

        > ./build.sh

6. Run the application

        > cd dev-tests
        > ./run.sh

7. Browse to [http://localhost:8087](http://localhost:8087/) or run one of the query* script located in folder 'dev-tests'.

dev-tests/run.sh uses docker-compose to start a full environment with Mesos, Zookeeper and Chronos, all of those are required for the proper execution of Woken.

## To use the development environment

5. You must launch and configure IntelliJ for native use

        > idea

        Open project->find the woken root you cloned->OK

        if you don't have the Java 8 SDK and JRE available, install it for your distribution.

        Configure the JDK "import project from sbt" by specifying the `/usr/lib/jvm/java-8-openjdk` directory.
        Then import, sbt will load the dependencies (this might take a while).

        configure the run configurations by the menu "Run->Edit configurations" then add "add->new application"

        Set the main class as web.Web
        Set the VM options : "-Dconfig.file=pathtowoken/dev-debug/woken/config/application.conf"
        Set the "Use classpath of module" to "Woken"

        Change the branch to AutoML
        > git checkout AutoML
        > git submodule update --init --recursive

        > cd dev-debug

        launch the `./run.sh`.

        If the image hbmip/woken-db-setup:b22cb6c doesn't pull:
        > git clone https://github.com/HBPMedical/woken-db-setup
        > cd woken-db-setup
        > ./build.sh

        if captain command not found, follow the installation instructions as shown here : https://github.com/harbur/captain

        Launch the IntelliJ woken with "Run".





## Available Docker containers

The Docker containers that can be executed on this platform require a few specific features.

TODO: define those features - parameters passed as environment variables, in and out directories, entrypoint with a 'compute command', ...

The project [functions-repository](https://github.com/LREN-CHUV/functions-repository) contains the Docker images that can be used with woken.

## Available commands

### Mining query

Performs a data mining task.

TODO: align this API with Exareme

Path: /mining
Verb: POST

Takes a Json document in the body, returns a Json document.

Json input should be of the form:

```
  {
    "variables": [],
    "covariables": [],
    "grouping": [],
    "filters": [],
    "algorithm": ""
  }
```

where:
* variables is the list of variables
* covariables is the list of covariables
* grouping is the list of variables to group together
* filters is the list of filters
* algorithm is the algorithm to use.

Currently, the following algorithms are supported:
* data: returns the raw data matching the query
* linearRegression: performs a linear regression
* summaryStatistics: performs a summary statistics than can be used to draw box plots.

## Docker build

Execute the following commands to distribute Workflow as a Docker container:

```
  ./build.sh
  cd docker
  ./build.sh
  ./push.sh
```

## Installation

For production, woken requires Mesos and Chronos. To install them, you can use either:

* [mip-microservices-infrastructure](https://github.com/LREN-CHUV/mip-microservices-infrastructure), a collection of Ansible scripts for deployment of a full Mesos stack on Ubuntu servers.
* [mantl.io](https://github.com/CiscoCloud/mantl), a microservice infrstructure by Cisco, based on Mesos.

# What's in a name?

Woken :
* the Woken river in China - we were looking for rivers in China
* passive form of awake - it launches Docker containers and computations
* workflow - the previous name, not too different
