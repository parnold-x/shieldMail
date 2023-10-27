# ShieldMail

This is a quick and dirty approach to achieve the following IMAP/Sieve behavior:

When an email is moved to an IMAP folder that is being watched, the program checks the sieve filter to see if the email address that the mail is from is already in a specific sieve filter from ShieldMail that moves all incoming emails from this address to the folder. If it is not, it automatically creates the sieve filter.

The watched folder can be configured in the properties file. If nothing is given, all folders under INBOX/ are watched.

This approach has worked well for me. If you would like to use it, feel free. I am open to tweaking it a little bit if you open an issue with your usage.
