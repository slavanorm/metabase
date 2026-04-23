git reset HEAD~1
rm ./backport.sh
git cherry-pick d51c5d501290d067f680cf3cec3e2550f2bce612
echo 'Resolve conflicts and force push this branch.\n\nTo backport translations run: bin/i18n/merge-translations <release-branch>'
