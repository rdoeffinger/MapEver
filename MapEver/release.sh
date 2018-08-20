VER=$(grep versionName AndroidManifest.xml | sed -e 's/.*"\(.*\)".*/v\1/')
cp build/outputs/mapping/release/mapping.txt mapping-$VER.txt
cp release/MapEver-release.apk MapEver-$VER.apk
gpg -a --detach-sign MapEver-$VER.apk
git tag -u 06D4D9C7 "$VER"
