#!/bin/bash
# Builds impala, with output redirected to $WORKSPACE/buildall.log
# TARGET_BUILD_TYPE can be set to Release/Debug

echo "********************************************************************************"
echo " building RecordService daemons."
echo "********************************************************************************"
pushd $IMPALA_HOME
echo "Build Args: $BUILD_ARGS"
./buildall.sh $BUILD_ARGS > $WORKSPACE/buildall.log 2>&1 ||\
    { tail -n 100 $WORKSPACE/buildall.log; echo "buildall.sh failed"; exit 1; }
popd
