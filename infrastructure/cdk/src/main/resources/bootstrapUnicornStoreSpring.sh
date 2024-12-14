set -e
echo "Copying unicorn-store-spring application source code to local directory ..."
mkdir -p ~/java-on-aws
git clone https://github.com/ybezsonov/java-on-aws-yb ~/java-on-aws/
cd ~/java-on-aws
git checkout refactor

mkdir -p ~/environment/unicorn-store-spring
rsync -av ~/java-on-aws/apps/unicorn-store-spring/ ~/environment/unicorn-store-spring --exclude target --exclude src/test
echo "target" >> ~/environment/unicorn-store-spring/.gitignore

echo "Seting up the local git repository in unicorn-store-spring ..."
cd ~/environment/unicorn-store-spring/
git -C ~/environment/unicorn-store-spring/ init -b main
git config --global user.email "you@workshops.aws"
git config --global user.name "Your Name"

echo "target/*" >> .gitignore
echo "*.jar" >> .gitignore
git -C ~/environment/unicorn-store-spring/ add .
git -C ~/environment/unicorn-store-spring/ commit -m "initial commit"

echo "Building the unicorn-store-spring application ..."
cd ~/environment/unicorn-store-spring
mvn clean package 1> /dev/null

echo '{ "query": { "folder": "/home/ec2-user/environment/unicorn-store-spring" } }' > /home/ec2-user/.local/share/code-server/coder.json
