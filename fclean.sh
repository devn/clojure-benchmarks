#! /bin/sh

# Find files that are automatically generated as output of runs, or by
# init.sh, to get a directory tree "distribution clean".

find . -name 'output' -o -name '*-expected-output.txt' -o -path './knuc/*-input.txt' -o -path './rcomp/*-input.txt'