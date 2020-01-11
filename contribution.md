## To run microsite 

```
cd zio-ftp
rm -r website/build/
./sbt ++2.12.10! docs/docusaurusCreateSite
cd ./website/build/zio-ftp/
npm run start
```