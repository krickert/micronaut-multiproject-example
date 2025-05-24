#!/bin/bash
#
rm current_proto.txt
for file in `find "$(pwd)/yappy-models/protobuf-models/src/main/proto" -name '*.proto' -not -path '*/build/*'`; do echo "CODE LISTING FOR ${file}" >> current_proto.txt; echo -e "\n\n" >> current_proto.txt; cat "$file" >> current_proto.txt; echo -e "END OF ${file}\n\n" >> current_proto.txt; done

