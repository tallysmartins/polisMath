FROM clojure

EXPOSE 3123

# Create app directory
ADD . /code
WORKDIR /code


CMD ["sleep", "infinity"]
#CMD ["npm", "start"]

