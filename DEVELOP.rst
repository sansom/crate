=============
Develop Guide
=============

Prerequisites
=============

CrateDB is written in Java_, so a JDK needs to be installed.

On OS X, we recommend using `Oracle's Java`_. If you're using Linux, we
recommend OpenJDK_.

We recommend you use a recent Java 8 version.

Setup
=====

Clone the repository like so::

    $ git clone https://github.com/crate/crate.git
    $ cd crate
    $ git submodule update --init

Manual Build
============

This project uses Gradle_ as build tool.

The most convenient way to  build and run CrateDB while you are working on the
code is to do so directly from within your IDE. See the section on IDE
integration later in this document. However, if you want to, you can work with
Gradle directly.

Gradle can be invoked by executing ``./gradlew``. The first time this command
is executed it is bootstrapped automatically, therefore there is no need to
install gradle on the system.

To compile the CrateDB sources, run::

    $ ./gradlew compileJava

To run CrateDB as a Gradle task, you need to create configuration file for
logging::

    $ mkdir -pv config && touch config/logging.yml

You can use a *minimal logging configuration*. For more information, see the
the `logging documentation`_.

Run CrateDB like so::

    $ ./gradlew runDebug

*Note*: If you run CrateDB like this, CrateDB will wait for a remote debugger
on port ``5005`` before fully starting up!

To install the CrateDB locally, run::

    $ ./gradlew installDist

And then start CrateDB like this::

    ./app/build/install/crate/bin/crate

Build a tarball like so::

    $ ./gradlew distTar

The tarball can then be found in the ``app/build/distributions`` directory.

Some other common build tasks::

    $ ./gradlew --parallel -PtestForks=2 :sql:test

    $ ./gradlew itest

    $ ./gradlew -PtestLogging :sql:test

    $ ./gradlew test -Dtest.single='YourTestClass'

    $ ./gradlew test --tests '*ClassName.testMethodName'

    $ ./gradlew :sql:test -Dtests.seed=8352BE0120F826A9

    $ ./gradlew :sql:test -Dtests.iters=20

Use ``@TestLogging(["<packageName1>:<logLevel1>", ...])`` on your test class or
test method to enable more detailed logging. For example::

    @TestLogging("io.crate:DEBUG","io.crate.planner.consumer.NestedLoopConsumer:TRACE")

Alternatively, you can set this configuration via the command line::

    $ ./gradlew -PtestLogging -Dtests.loggers.levels=io.crate:DEBUG,io.crate.planner.consumer.NestedLoopConsumer:TRACE :sql:test

If you do this, the setting is applied to all tests that are run.

To get a full list of all available tasks, run::

    $ ./gradlew tasks

Using an IDE
============

We recommend that you use `IntelliJ IDEA`_ to develop CrateDB.

Gradle can be used to generate project files that can be opened in IntelliJ::

    $ ./gradlew idea

Run/Debug Configurations
------------------------

Running ``./gradlew idea`` creates a Run/Debug configuration called ``Crate``.
This configuration can be used to launch and debug CrateDB from within IntelliJ.

The ``home`` directory will be set to ``<project_root>/sandbox/crate`` and the
configuration files can be found in the ``<project_root>/sandbox/crate/config``
directory.

Test Coverage
=============

You can create test coverage reports with `jacoco`_::

    $ ./gradlew jacocoReport

The HTML test coverage report can then be found in the
``build/reports/jacoco/jacocoHtml`` directory.

FindBugs
========

You can run `FindBugs`_ like so::

    $ ./gradlew findBugsMain

The FindBugs check will also be executed when running ``./gradlew check``.

Forbidden APIs
==============

You can run the `Forbidden APIs`_ tool like so::

    $ ./gradlew forbiddenApisMain

Benchmarks
==========

External Benchmarks
-------------------

External benchmarks use a CrateDB client to execute SQL statements against one
or more CrateDB nodes.

You can run the external benchmarks like so::

    $ ./gradlew externalBenchmarks

Internal Benchmarks
-------------------

Internal benchmarks test specific components or units within CrateDB.

We previously used to write them using JUnitBenchmarks, but that project has
been deprecated in favor of `JMH`_.

The benchmarks that were written using JUnitBenchmarks can still be run using::

    $ ./gradlew benchmarks

These benchmarks will eventually be replaced with benchmarks that use `JMH`_.

JMH Benchmarks
--------------

`JMH`_ benchmarks can be run with Gradle::

    $ ./gradlew :core:jmh
    $ ./gradlew :sql:jmh

By default, these commands will look for benchmarks inside
``<module>/src/jmh/java`` and execute them.

If you want to execute specific benchmarks you can use the jar file, like so::

    $ ./gradlew :sql:jmhJar
    $ java -jar sql/build/libs/crate-sql-jmh.jar <benchmarkMethodName>

Results will be generated and written to ``$buildDir/reports/jmh``.

If you're writing new benchmarks, take a look at the `JMH introduction`_ and
`JMH samples`_.

Preparing a New Release
=======================

Before creating a new distribution, a new version and tag should be created:

- Update ``CURRENT`` in ``io.crate.Version``
- Add a section for the new version in the ``CHANGES.txt`` file
- Commit your changes with a message like "prepare release x.y.z"
- Push to origin
- Create a tag by running ``./devtools/create_tag.sh``

You can build a release tarball like so::

    $ ./gradlew release

This task runs the ``distTar`` task but also checks that the output of ``git
describe --tag`` matches the current version of CrateDB.

The resulting tarball and zip file will be written to the
``./app/build/distributions`` directory.

We have a Jenkins_ job that will build the tarball for you.

Navigating the Code
===================

Getting familiar with a foreign code base is often a daunting task.

At the moment, we don't have a full guide to the whole code architecture, but
this section should give you an idea of where to look.

When a SQL statement is sent to CrateDB, the work-flow is roughly as follows:

- Handle the HTTP request

- Parse the request body and create an SQLRequest object (see
  ``RestSQLAction.java``)

- Process the SQLRequest object (see ``doExecute`` in
  ``TransportBaseSQLAction.java``)

  - The statement is parsed, resulting in an *Abstract Syntax Tree* (AST)

  - The AST is analyzed and annotated using metadata like details about the schema

  - Some statements (mostly DDL) are executed directly

  - The planner creates a plan for other statements (``SELECT``, ``UPDATE``,
    ``DELETE`` and so on)

  - The executor executes the statement

Writing Documentation
=====================

The docs live under the ``blackbox/docs`` directory.

The docs are written with `reStructuredText`_ and built with Sphinx_.

Line length must not exceed 80 characters (except for literals that cannot be
wrapped). Most text editors support automatic line breaks or hard wrapping at a
certain line width if you don't want to do this by hand.

To start working on the docs locally, you will need Python_ 3 in addition to
Java_ (needed for the doctests_). Make sure that ``python3`` is on your
``$PATH``.

Before you can get started, you need to bootstrap the docs::

    $ cd blackbox
    $ ./bootstrap.sh

Once this runs, you can build the docs and start the docs web server like so::

    $ ./bin/sphinx dev

Once the web server running, you can view your local copy of the docs by
visiting http://127.0.0.1:8000 in a web browser.

This command also watches the file system and rebuilds the docs when changes
are detected. Even better, it will automatically refresh the browser tab for
you.

Many of the examples in the documentation are executable and function as
doctests_.

You can run the doctests like so::

    $ ./bin/test

If you want to test the doctests in a specific file, run this::

    $ ./bin/test -1vt <filename>

There is also a Gradle task called ``itest`` which will execute all of the
above steps.

*Note*: Your network connection should be up and running, or some of the tests
will fail. CrateDB needs to bind to a network interface that is capable of
handling multicast. This is not possible on localhost.

The docs are automatically built from Git by `Read the Docs`_ and there is
nothing special you need to do to get the live docs to update.

Troubleshooting
===============

If you just pulled some new commits and you're getting strange compile errors
in the SQL parser code, try re-generating the code::

    $ ./gradlew :sql-parser:compileJava

.. _doctests: http://www.sphinx-doc.org/en/stable/ext/doctest.html
.. _FindBugs: http://findbugs.sourceforge.net/
.. _Forbidden APIs: https://github.com/policeman-tools/forbidden-apis
.. _Gradle: http://www.gradle.org/
.. _IntelliJ IDEA: https://www.jetbrains.com/idea/
.. _jacoco: http://www.eclemma.org/jacoco/
.. _Java: http://www.java.com/
.. _Jenkins: http://jenkins-ci.org/
.. _JMH introduction: http://java-performance.info/jmh/
.. _JMH samples: http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/
.. _JMH: http://openjdk.java.net/projects/code-tools/jmh/
.. _logging documentation: https://crate.io/docs/en/stable/configuration.html#logging
.. _OpenJDK: http://openjdk.java.net/projects/jdk8/
.. _Oracle's Java: http://www.java.com/en/download/help/mac_install.xml
.. _Python: http://www.python.org/
.. _Read the Docs: http://readthedocs.org
.. _reStructuredText: http://docutils.sourceforge.net/rst.html
.. _Sphinx: http://sphinx-doc.org/
