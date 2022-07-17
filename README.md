# RSWebAPI

A (mostly experimental) [Refined Storage](https://github.com/refinedmods/refinedstorage) addon that allows you to access the storage network via the internal webserver.
The interface outputs the data in a JSON format, and is therefore compatible with any language that can parse JSON.

To have the web interface recognize your storage network, you must rename any item to this format: "rsweb-<name>", and then insert it into your storage.

TODO: Write a cache/hardlimit with requests to prevent abuse.