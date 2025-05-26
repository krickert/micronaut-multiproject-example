#!/bin/bash
#
rm current_yappy_bootstrap.txt
for file in `find "$(pwd)/yappy-engine/src/main/java/com/krickert/yappy" -name '*.java' -not -path '*/build/*'`; do echo "CODE LISTING FOR ${file}" >> current_yappy_bootstrap.txt; echo -e "\n\n" >> current_yappy_bootstrap.txt; cat "$file" >> current_yappy_bootstrap.txt; echo -e "END OF ${file}\n\n" >> current_yappy_bootstrap.txt; done
for file in `find "$(pwd)/yappy-engine/src/test/java/com/krickert/yappy" -name '*.java' -not -path '*/build/*'`; do echo "CODE LISTING FOR ${file}" >> current_yappy_bootstrap.txt; echo -e "\n\n" >> current_yappy_bootstrap.txt; cat "$file" >> current_yappy_bootstrap.txt; echo -e "END OF ${file}\n\n" >> current_yappy_bootstrap.txt; done

