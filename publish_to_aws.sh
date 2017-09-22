#!/bin/bash
cd /Users/jankeesvanandel/projects/work/devoxx/devoxx-twitterproxy
echo "Building distribution"
sbt dist
echo "Publishing to AWS"

AWS_HOST=ec2-54-194-162-191.eu-west-1.compute.amazonaws.com
AWS_USER=ec2-user
AWS_KEY=~/.ssh/DevoxxWallKeyPair2015.pem

scp -v -i $AWS_KEY target/universal/devoxx-twitterproxy-*.zip $AWS_USER@$AWS_HOST:~
ssh -v -t -i $AWS_KEY $AWS_USER@$AWS_HOST "sudo /etc/init.d/devoxx-twitterproxy stop && rm -rf /opt/devoxx-twitterproxy/* && unzip devoxx-twitterproxy-*.zip -d /opt/devoxx-twitterproxy && sudo /etc/init.d/devoxx-twitterproxy start"

echo "Done"
