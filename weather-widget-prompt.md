# weather widget prompt ** \-  Wed 28 Jan 26  11:00 AM**

weather widget features:

1. Use nws api and as a backup or alternatively switch dynamically with open-meteo if it is completely free.

2. android widget

3. resizeable widget

4. display yesterday's data \+ Prediction.

5. Display in graphical format as much as possible

   1. graphical : display something like error bars with high and low forecast

6. If too small, one row height, then skip the graphs.

7. if 1x1 in size then just display forecast high for today.  If size allows current temp also.

8. Display more info as size increases.

9. if size is 1x3 , display past, today and tomorrow.

10. If size 2x3 display same info but try to make it graphical. 2 rows x 3 columns

11. write tests for each feature  
12. Visual Style: I like apple glass, but if that is too hard, lets try google material  
13. have option to specify location using current location or zip code.  Default to google headquarters if no location provided.  
14. For history, keep a record.  
    1. historical data retention: Lets keep it for a month, but can limit to just one day, since that is all that is needed for current requirements   
15. Add plenty of logging to help diagnose issues.  
16. Add tests for each feature  
17. If 4 cols wide then display 2 days of forecast in addition to yesterday and today.  
18. if 5 columns wide then 3 days of forecast, etc…  
19. 

