set -e

mkdir -p ~/environment/unicorn-store-spring
rsync -av ~/java-on-aws/apps/unicorn-store-spring/ ~/environment/unicorn-store-spring --exclude target --exclude src/test
cd ~/environment/unicorn-store-spring
echo "target" >> .gitignore

echo "Seting up the local git repository ..."
git init -b main
git config --global user.email "you@workshops.aws"
git config --global user.name "Your Name"

echo "target/*" >> .gitignore
echo "*.jar" >> .gitignore
git add .
git commit -m "initial commit"

echo "Building the application ..."
mvn clean package 1> /dev/null

echo '{ "query": { "folder": "/home/ec2-user/environment/unicorn-store-spring" } }' > /home/ec2-user/.local/share/code-server/coder.json
