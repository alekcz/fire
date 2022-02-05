#!/bin/bash
chmod +x ./test.sh        
firebase emulators:exec ./test.sh --import=./test/resources