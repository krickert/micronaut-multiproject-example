#!/bin/bash
#
rm current_java.txt
for file in `find "$(pwd)" -name '*.java' -not -path '*/build/*'`; do echo "CODE LISTING FOR ${file}" >> current_java.txt; echo -e "\n\n" >> current_java.txt; cat "$file" >> current_java.txt; echo -e "END OF ${file}\n\n" >> current_java.txt; done

