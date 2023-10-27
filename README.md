# ShieldMail

This is a QnD approach to achive the following IMAP/Sieve Behavior

When a Email is moved to a IMAP Folder that is watched the program checks the sieve filter if this email address that the mail is from is already in a specific sieve filter that moves all incoming emails from this address to the folder.
If not it creates automatically the sieve filter.

The watched folder can be configured in the properties file or if nothin is given all folders under INBOX/ are watched.

This works for me fine, if you want to use it feel free and i am open to tweak it a little bit when you open a issue with your usage.
