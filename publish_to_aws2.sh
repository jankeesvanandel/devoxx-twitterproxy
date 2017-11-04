#!/bin/bash
cd ~/projects/work/devoxx/devoxx-twitterproxy
echo "Building distribution"
sbt dist

rm -rf dist
mkdir dist
cp target/universal/devoxx-twitterproxy-*.zip dist/
cp appspec.yml dist/
cp -r aws_scripts dist/
echo "Publishing to AWS"

aws deploy push --application-name devoxx-twitterproxy --s3-location s3://<bucketname>/twitterproxy.zip --source dist --profile jankeesvanandel
aws deploy create-deployment --application-name devoxx-twitterproxy --s3-location bucket=<bucketname>,key=twitterproxy.zip,bundleType=zip --deployment-group-name devoxx-twitterproxy --profile jankeesvanandel

echo "Done"
