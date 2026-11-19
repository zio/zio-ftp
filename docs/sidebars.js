const sidebars = {
  sidebar: [
    "index",
    "getting-started",
    "protocols",
    "environment",
    {
      type: "category",
      label: "Reference",
      collapsed: false,
      items: [
        "reference/ftp-accessors",
        "reference/connecting",
        "reference/unsecure-ftp-settings",
        "reference/secure-ftp-settings",
        "reference/ftp-resource",
        "reference/errors",
        "reference/test-ftp"
      ]
    },
    "tutorials/back-up-a-folder",
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "guides/upload-a-file",
        "guides/download-a-file",
        "guides/list-a-directory-recursively",
        "guides/connect-over-sftp-with-a-key",
        "guides/use-ftps",
        "guides/keep-a-connection-alive",
        "guides/test-without-a-server",
        "guides/call-an-unwrapped-client-method"
      ]
    }
  ]
};

module.exports = sidebars;
