#!/bin/bash

rm current_java.txt
for file in `ls *.java`; do echo -e "\nCODE LISTING FOR ${file}" >> current_java.txt; echo -e "\n\n" >> current_java.txt; cat $file >> current_java.txt; echo -e "END OF ${file}\n\n" >> current_java.txt; done;

